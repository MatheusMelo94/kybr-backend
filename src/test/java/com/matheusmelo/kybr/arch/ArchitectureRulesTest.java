package com.matheusmelo.kybr.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.matheusmelo.kybr.config.filter.PathFirewallFilter;
import com.matheusmelo.kybr.util.Cnpj;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/** The layer rules and B0's web guarantees, enforced on production classes. */
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
