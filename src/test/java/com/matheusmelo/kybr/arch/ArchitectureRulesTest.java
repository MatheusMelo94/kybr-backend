package com.matheusmelo.kybr.arch;

import static com.tngtech.archunit.core.domain.JavaCall.Predicates.target;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.properties.CanBeAnnotated.Predicates.annotatedWith;
import static com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner;
import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noCodeUnits;

import com.matheusmelo.kybr.config.SlugTagLimit;
import com.matheusmelo.kybr.config.filter.PathFirewallFilter;
import com.matheusmelo.kybr.model.link.AuditEvent;
import com.matheusmelo.kybr.repository.AuditEventRepository;
import com.matheusmelo.kybr.service.MetricTags;
import com.matheusmelo.kybr.service.idempotency.Idempotent;
import com.matheusmelo.kybr.util.Cnpj;
import com.mongodb.client.MongoCluster;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.UpdateOptions;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.filter.ShallowEtagHeaderFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.constantpool.PoolEntry;
import java.lang.classfile.constantpool.StringEntry;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/** The layer rules, the web guarantees and the data and logging rules, enforced on production classes. */
@AnalyzeClasses(packages = "com.matheusmelo.kybr", importOptions = ImportOption.DoNotIncludeTests.class)
public class ArchitectureRulesTest {

    @ArchTest
    public static final ArchRule CONTROLLERS_DO_NOT_USE_REPOSITORIES = noClasses()
            .that().resideInAPackage("..controller..")
            .should().dependOnClassesThat().resideInAPackage("..repository..");

    @ArchTest
    public static final ArchRule UTIL_HAS_NO_SPRING = noClasses()
            .that().resideInAPackage("..util..")
            .should().dependOnClassesThat().resideInAPackage("org.springframework..");

    @ArchTest
    public static final ArchRule MODELS_DO_NOT_USE_DTOS = noClasses()
            .that().resideInAPackage("..model..")
            .should().dependOnClassesThat().resideInAPackage("..dto..");

    @ArchTest
    public static final ArchRule NO_REDIRECTS_COOKIES_OR_SESSIONS = noClasses()
            .should(callServletMethod(HttpServletResponse.class, "sendRedirect"))
            .orShould(callServletMethod(HttpServletResponse.class, "addCookie"))
            .orShould(callServletMethod(HttpServletRequest.class, "getSession"));

    /**
     * The API authenticates with explicit headers only, never cookies, which is why CSRF protection
     * is off (see SecurityConfig). Reading a cookie anywhere fails the build, so cookie-based auth
     * cannot be added without revisiting that decision.
     */
    @ArchTest
    public static final ArchRule NO_COOKIE_READS = noClasses()
            .should(callServletMethod(HttpServletRequest.class, "getCookies"))
            .orShould().dependOnClassesThat()
            .haveFullyQualifiedName("org.springframework.web.bind.annotation.CookieValue");

    @ArchTest
    public static final ArchRule NO_X_FORWARDED_FOR = noClasses()
            .should(referenceStringConstant("X-Forwarded-For"));

    /** Canonicalisation happens in the service layer, never at the boundary. */
    @ArchTest
    public static final ArchRule NO_CNPJ_CANONICALIZE_AT_BOUNDARY = noClasses()
            .that().resideInAnyPackage("..controller..", "..dto..")
            .should().callMethod(Cnpj.class, "canonicalize", String.class);

    /**
     * The one exemption to {@link #FILTERS_DO_NOT_ROUTE_ON_RAW_URI}: the access log reads the raw
     * URI only to log it, redacted, when no handler pattern matched (or Tomcat rejected the request
     * before any filter ran). It never routes or decides on it. Exempt by method, so any other use
     * of the raw URI in these classes still fails.
     */
    static final Set<String> RAW_URI_LOGGING_ONLY = Set.of(
            "com.matheusmelo.kybr.config.filter.AccessLogFilter.route",
            "com.matheusmelo.kybr.config.filter.AccessLogFilter.errorUri",
            "com.matheusmelo.kybr.config.filter.AccessLogValve.invoke");

    /** Filters route on the cached {@code RequestPath}, never the raw URI. */
    @ArchTest
    public static final ArchRule FILTERS_DO_NOT_ROUTE_ON_RAW_URI = noClasses()
            .that().resideInAPackage("..config.filter..")
            .and().doNotBelongToAnyOf(PathFirewallFilter.class)
            .should(callServletMethod(HttpServletRequest.class, "getRequestURI", RAW_URI_LOGGING_ONLY));

    /**
     * No API response is conditional: the response guard turns any 3xx, 304 included, into a 500,
     * so nothing may set a validator header or answer "not modified".
     */
    @ArchTest
    public static final ArchRule NO_CONDITIONAL_RESPONSES = noClasses()
            .should(callMethodNamed(ResponseEntity.HeadersBuilder.class, "eTag"))
            .orShould(callMethodNamed(ResponseEntity.HeadersBuilder.class, "lastModified"))
            .orShould(callMethodNamed(WebRequest.class, "checkNotModified"))
            .orShould().dependOnClassesThat().areAssignableTo(ShallowEtagHeaderFilter.class);

    /** Entity to DTO conversion happens in a mapper: DTOs never carry an entity or a model type. */
    @ArchTest
    public static final ArchRule DTOS_DO_NOT_USE_MODELS = noClasses()
            .that().resideInAPackage("..dto..")
            .should().dependOnClassesThat().resideInAPackage("..model..");

    /**
     * Services and repositories never see an API DTO; a mapper converts at the boundary. The
     * declarative validators' normalisers are shared, so the service applies the same rule the
     * boundary checked.
     */
    @ArchTest
    public static final ArchRule SERVICES_DO_NOT_USE_API_DTOS = noClasses()
            .that().resideInAnyPackage("..service..", "..repository..")
            .should().dependOnClassesThat(resideInAPackage("..dto..").and(not(resideInAPackage("..dto.validation.."))));

    /** Controllers pass entities on but never read them; a mapper builds the response. */
    @ArchTest
    public static final ArchRule CONTROLLERS_DO_NOT_READ_ENTITIES = noClasses()
            .that().resideInAPackage("..controller..")
            .should().callMethodWhere(target(owner(annotatedWith(Document.class))));

    /**
     * Tests run MongoDB 7.0 and production runs 8: no client-level bulk write across collections and
     * no {@code updateOne}/{@code replaceOne} with {@code sort} (claims use {@code findOneAndUpdate}).
     * Limit: only the driver-level options are caught. A Spring Data {@code updateFirst} or
     * {@code replace} given a {@code Query} with a sort isn't visible to bytecode rules; such a
     * query is reviewed by hand, and the custom repository components document their writes.
     */
    @ArchTest
    public static final ArchRule NO_MONGO_8_ONLY_WRITES = noClasses()
            .should(callMethodNamed(MongoCluster.class, "bulkWrite"))
            .orShould(callMethodNamed(UpdateOptions.class, "sort"))
            .orShould(callMethodNamed(ReplaceOptions.class, "sort"))
            .orShould().dependOnClassesThat().resideInAPackage("com.mongodb.client.model.bulk..");

    /**
     * Every idempotent handler states how it completes. The annotation has no default today; this
     * keeps a default from being added later, which would let a handler skip the choice.
     */
    @ArchTest
    public static final ArchRule IDEMPOTENT_HANDLERS_DECLARE_COMPLETION = methods()
            .that(annotatedWithSimpleName(Idempotent.class.getSimpleName()))
            .should(declareAnnotationProperty(Idempotent.class.getSimpleName(), "completion"));

    /**
     * Types that carry a provider's response body. A provider adapter method that holds one (as a
     * parameter, a call result or a caught exception) never logs, so a body can't reach a log line;
     * it hands a type and status to a method that does.
     */
    static final Set<Class<?>> PROVIDER_RESPONSE_TYPES =
            Set.of(JsonNode.class, ClientHttpResponse.class, RestClientResponseException.class);

    @ArchTest
    public static final ArchRule PROVIDER_RESPONSES_ARE_NOT_LOGGED = noCodeUnits()
            .that().areDeclaredInClassesThat().resideInAPackage("..service.company..")
            .should(logWhileHolding(PROVIDER_RESPONSE_TYPES));

    /** Mongo writes that change or remove existing documents ({@code insert} only appends). */
    static final Set<String> MUTATING_MONGO_METHODS = Set.of("save", "saveAll", "delete", "deleteById", "deleteAll",
            "deleteAllById", "updateFirst", "updateMulti", "upsert", "update", "remove", "findAndModify",
            "findAndRemove", "findAllAndRemove", "findAndReplace", "replace", "bulkOps", "updateOne", "updateMany",
            "replaceOne", "deleteOne", "deleteMany", "findOneAndUpdate", "findOneAndReplace", "findOneAndDelete",
            "bulkWrite");

    /**
     * Audit events are append-only: nothing saves over, updates or deletes one, through the
     * repository, the template or the driver.
     */
    @ArchTest
    public static final ArchRule AUDIT_EVENTS_ARE_APPEND_ONLY = noCodeUnits()
            .should(mutateAuditEvents());

    /**
     * A {@code slug} metric tag comes from {@link MetricTags#slug}, so request strings can't create
     * time series. A class that registers meters and names the tag must derive the value there.
     */
    @ArchTest
    public static final ArchRule SLUG_TAGS_GO_THROUGH_METRIC_TAGS = noClasses()
            .that().doNotBelongToAnyOf(MetricTags.class, SlugTagLimit.class)
            .should(tagMetersWithARawSlug());

    /** A call to any overload of {@code method} declared on {@code type} or a subtype. */
    static ArchCondition<JavaClass> callMethodNamed(Class<?> type, String method) {
        return callServletMethod(type, method, Set.of());
    }

    static ArchCondition<JavaClass> callServletMethod(Class<?> servletType, String method) {
        return callServletMethod(servletType, method, Set.of());
    }

    /**
     * A call to {@code method} on any {@code servletType}, including container and wrapper types,
     * from a caller not in {@code exemptCallers} ({@code fully.qualified.Class.method}). A wrapper's
     * override delegating to its own overload (a self call) is not a servlet call.
     */
    static ArchCondition<JavaClass> callServletMethod(Class<?> servletType, String method, Set<String> exemptCallers) {
        return new ArchCondition<>("call " + servletType.getSimpleName() + "." + method) {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                for (JavaMethodCall call : javaClass.getMethodCallsFromSelf()) {
                    JavaClass owner = call.getTargetOwner();
                    String caller = call.getOriginOwner().getName() + "." + call.getOrigin().getName();
                    if (call.getName().equals(method) && owner.isAssignableTo(servletType)
                            && !owner.equals(call.getOriginOwner()) && !exemptCallers.contains(caller)) {
                        events.add(SimpleConditionEvent.satisfied(call, call.getDescription()));
                    }
                }
            }
        };
    }

    /**
     * A class whose constant pool holds {@code value} (any case): catches header-name literals and
     * inlined constants, which ArchUnit's call graph can't see. Comments never reach bytecode.
     */
    static ArchCondition<JavaClass> referenceStringConstant(String value) {
        byte[] needle = value.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.ISO_8859_1);
        return new ArchCondition<>("reference \"" + value + "\"") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                if (contains(lowerCase(classBytes(javaClass)), needle)) {
                    events.add(SimpleConditionEvent.satisfied(javaClass,
                            javaClass.getName() + " references \"" + value + "\""));
                }
            }
        };
    }

    private static DescribedPredicate<JavaCodeUnit> annotatedWithSimpleName(String simpleName) {
        return DescribedPredicate.describe("annotated with @" + simpleName, unit -> unit.getAnnotations().stream()
                .anyMatch(annotation -> annotation.getRawType().getSimpleName().equals(simpleName)));
    }

    private static ArchCondition<JavaMethod> declareAnnotationProperty(String simpleName, String property) {
        return new ArchCondition<>("declare @" + simpleName + "(" + property + ") explicitly") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                for (JavaAnnotation<JavaMethod> annotation : method.getAnnotations()) {
                    if (annotation.getRawType().getSimpleName().equals(simpleName)
                            && !annotation.hasExplicitlyDeclaredProperty(property)) {
                        events.add(SimpleConditionEvent.violated(method,
                                method.getFullName() + " leaves " + property + " to its default"));
                    }
                }
            }
        };
    }

    private static ArchCondition<JavaCodeUnit> logWhileHolding(Set<Class<?>> types) {
        return new ArchCondition<>("log while holding a provider response") {
            @Override
            public void check(JavaCodeUnit unit, ConditionEvents events) {
                boolean logs = unit.getMethodCallsFromSelf().stream()
                        .anyMatch(call -> call.getTargetOwner().isAssignableTo(Logger.class));
                if (logs && holdsAny(unit, types)) {
                    events.add(SimpleConditionEvent.satisfied(unit,
                            unit.getFullName() + " logs while holding a provider response"));
                }
            }
        };
    }

    private static boolean holdsAny(JavaCodeUnit unit, Set<Class<?>> types) {
        DescribedPredicate<JavaClass> provider = DescribedPredicate.describe("provider response",
                type -> types.stream().anyMatch(type::isAssignableTo));
        return unit.getRawParameterTypes().stream().anyMatch(provider)
                || unit.getMethodCallsFromSelf().stream()
                        .anyMatch(call -> provider.test(call.getTarget().getRawReturnType()))
                || unit.getTryCatchBlocks().stream()
                        .anyMatch(block -> block.getCaughtThrowables().stream().anyMatch(provider));
    }

    private static ArchCondition<JavaCodeUnit> mutateAuditEvents() {
        return new ArchCondition<>("change or remove an audit event") {
            @Override
            public void check(JavaCodeUnit unit, ConditionEvents events) {
                for (JavaMethodCall call : unit.getMethodCallsFromSelf()) {
                    JavaClass owner = call.getTargetOwner();
                    if (!MUTATING_MONGO_METHODS.contains(call.getName())) {
                        continue;
                    }
                    boolean viaRepository = owner.isAssignableTo(AuditEventRepository.class);
                    boolean viaMongo = (owner.isAssignableTo(MongoOperations.class)
                            || owner.isAssignableTo(MongoCollection.class)) && touchesAuditEvents(unit);
                    if (viaRepository || viaMongo) {
                        events.add(SimpleConditionEvent.satisfied(call, call.getDescription()));
                    }
                }
            }
        };
    }

    /** The code unit names the audit entity, takes one, or its class names the audit collection. */
    private static boolean touchesAuditEvents(JavaCodeUnit unit) {
        return unit.getReferencedClassObjects().stream().anyMatch(ref -> ref.getRawType().isEquivalentTo(AuditEvent.class))
                || unit.getRawParameterTypes().stream().anyMatch(type -> type.isEquivalentTo(AuditEvent.class))
                || hasStringLiteral(classBytes(unit.getOwner()), AuditEvent.COLLECTION);
    }

    private static final Set<Class<?>> METER_BUILDERS = Set.of(MeterRegistry.class, Counter.Builder.class,
            Timer.Builder.class, Gauge.Builder.class, DistributionSummary.Builder.class, Tags.class, Tag.class);

    private static ArchCondition<JavaClass> tagMetersWithARawSlug() {
        return new ArchCondition<>("tag meters with a slug not taken from MetricTags.slug") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                Set<JavaMethodCall> calls = javaClass.getMethodCallsFromSelf();
                boolean registers = calls.stream().anyMatch(
                        call -> METER_BUILDERS.stream().anyMatch(call.getTargetOwner()::isAssignableTo));
                boolean bounded = calls.stream().anyMatch(call -> call.getTargetOwner().isEquivalentTo(MetricTags.class)
                        && call.getName().equals("slug"));
                if (registers && !bounded && hasStringLiteral(classBytes(javaClass), MetricTags.SLUG)) {
                    events.add(SimpleConditionEvent.satisfied(javaClass,
                            javaClass.getName() + " tags meters with a slug not taken from MetricTags.slug"));
                }
            }
        };
    }

    /** A string literal (not a field, method or variable name) equal to {@code value}. */
    private static boolean hasStringLiteral(byte[] classFile, String value) {
        for (PoolEntry entry : ClassFile.of().parse(classFile).constantPool()) {
            if (entry instanceof StringEntry string && string.stringValue().equals(value)) {
                return true;
            }
        }
        return false;
    }

    private static byte[] classBytes(JavaClass javaClass) {
        var source = javaClass.getSource().orElseThrow(
                () -> new IllegalStateException("no class file for " + javaClass.getName()));
        try (InputStream in = source.getUri().toURL().openStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] lowerCase(byte[] bytes) {
        byte[] out = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            byte b = bytes[i];
            out[i] = b >= 'A' && b <= 'Z' ? (byte) (b + 32) : b;
        }
        return out;
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}
