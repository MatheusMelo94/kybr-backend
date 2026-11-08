package com.matheusmelo.kybr.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noCodeUnits;

import com.matheusmelo.kybr.service.TransactionRetry;
import com.matheusmelo.kybr.service.email.EmailSender;
import com.matheusmelo.kybr.service.email.ResendEmailSender;
import com.matheusmelo.kybr.service.image.SanitizerClient;
import com.matheusmelo.kybr.service.scan.ClamdScanner;
import com.matheusmelo.kybr.service.storage.S3DocumentStorage;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.domain.properties.HasAnnotations;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import jakarta.servlet.http.Part;
import java.lang.classfile.ClassFile;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.constant.ConstantDesc;
import java.lang.constant.DirectMethodHandleDesc;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * The upload and e-mail rules: no client filename is ever read, the live scanner, sanitizer,
 * storage and e-mail clients exist only behind the modes the prod profile checks, and no e-mail
 * leaves inside a transaction that may be re-run.
 */
@AnalyzeClasses(packages = "com.matheusmelo.kybr", importOptions = ImportOption.DoNotIncludeTests.class)
public class UploadAndEmailRulesTest {

    /** The live clients: each reaches a real external system. */
    static final Set<String> LIVE_CLIENTS = Set.of(ClamdScanner.class.getName(), SanitizerClient.class.getName(),
            S3DocumentStorage.class.getName(), S3Client.class.getName(), ResendEmailSender.class.getName());

    /**
     * {@code property=value} for each live mode. {@code ProfileGuards} refuses to start the prod
     * profile unless every one of these is the mode in force, so a live client gated on one of them
     * is a client the prod profile has checked.
     */
    static final Map<String, String> GUARDED_MODES = Map.of(
            "kybr.scanner.mode", "clamd",
            "kybr.image.mode", "remote",
            "kybr.storage.mode", "s3",
            "kybr.email.mode", "resend");

    /**
     * The stored name is the server's; whatever name the client sent is never read, so it can't
     * reach a key, a log line or a response.
     */
    @ArchTest
    public static final ArchRule NO_CLIENT_FILENAMES = noClasses()
            .should(ArchitectureRulesTest.callMethodNamed(MultipartFile.class, "getOriginalFilename"))
            .orShould(ArchitectureRulesTest.callMethodNamed(Part.class, "getSubmittedFileName"));

    @ArchTest
    public static final ArchRule LIVE_CLIENT_BEANS_ARE_MODE_GATED = liveClientBeansAreModeGated(LIVE_CLIENTS);

    @ArchTest
    public static final ArchRule LIVE_CLIENTS_ARE_BUILT_ONLY_BY_CONFIGURATION =
            liveClientsAreBuiltOnlyByConfiguration(LIVE_CLIENTS);

    /**
     * A transaction callback may run several times, and a send can't be rolled back: mail is queued
     * in the outbox inside the transaction and sent after it. Limit: calls are followed through
     * concrete methods only; a call through an interface other than {@link EmailSender} isn't
     * followed into its implementations.
     */
    @ArchTest
    public static final ArchRule NO_EMAIL_SEND_INSIDE_TRANSACTION_RETRY = noCodeUnits()
            .should(sendEmailInsideTransactionRetry());

    /**
     * Every bean of a live client type, from a {@code @Bean} method or a stereotype, is conditional on
     * its guarded mode (on the method or its configuration class).
     */
    static ArchRule liveClientBeansAreModeGated(Set<String> liveClients) {
        return noClasses().should(new ArchCondition<>("declare a live client bean outside its guarded mode") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                if (isLiveClient(javaClass, liveClients) && isStereotype(javaClass) && !guarded(javaClass)) {
                    events.add(SimpleConditionEvent.satisfied(javaClass,
                            javaClass.getName() + " is a live client component without a guarded mode"));
                }
                for (JavaMethod method : javaClass.getMethods()) {
                    if (method.isAnnotatedWith(Bean.class) && isLiveClient(method.getRawReturnType(), liveClients)
                            && !guarded(method) && !guarded(javaClass)) {
                        events.add(SimpleConditionEvent.satisfied(method,
                                method.getFullName() + " declares a live client without a guarded mode"));
                    }
                }
            }
        });
    }

    /**
     * Only configuration classes (whose beans the rule above gates) construct a live client, with
     * {@code new} or a static {@code builder()}.
     */
    static ArchRule liveClientsAreBuiltOnlyByConfiguration(Set<String> liveClients) {
        return noClasses()
                .that().areNotAnnotatedWith(Configuration.class)
                .should(new ArchCondition<>("construct a live client") {
                    @Override
                    public void check(JavaClass javaClass, ConditionEvents events) {
                        javaClass.getConstructorCallsFromSelf().stream()
                                .filter(call -> isLiveClient(call.getTargetOwner(), liveClients))
                                .filter(call -> !call.getTargetOwner().equals(javaClass))
                                .forEach(call -> events.add(SimpleConditionEvent.satisfied(call, call.getDescription())));
                        javaClass.getMethodCallsFromSelf().stream()
                                .filter(call -> call.getName().equals("builder")
                                        && isLiveClient(call.getTargetOwner(), liveClients))
                                .forEach(call -> events.add(SimpleConditionEvent.satisfied(call, call.getDescription())));
                    }
                });
    }

    private static ArchCondition<JavaCodeUnit> sendEmailInsideTransactionRetry() {
        return new ArchCondition<>("send an e-mail inside a TransactionRetry callback") {
            @Override
            public void check(JavaCodeUnit unit, ConditionEvents events) {
                boolean retries = unit.getMethodCallsFromSelf().stream().anyMatch(
                        call -> call.getTargetOwner().isAssignableTo(TransactionRetry.class)
                                && call.getName().equals("execute"));
                if (retries) {
                    findEmailSend(unit).ifPresent(send -> events.add(SimpleConditionEvent.satisfied(unit,
                            unit.getFullName() + " runs a TransactionRetry callback that reaches "
                                    + send.getDescription())));
                }
            }
        };
    }

    /**
     * The first {@link EmailSender} call reachable from {@code start}, following calls and the
     * lambdas each method creates (a lambda body is a separate synthetic method that ArchUnit doesn't
     * link to the method declaring it).
     */
    private static Optional<JavaMethodCall> findEmailSend(JavaCodeUnit start) {
        Deque<JavaCodeUnit> pending = new ArrayDeque<>(Set.of(start));
        Set<JavaCodeUnit> seen = new HashSet<>();
        while (!pending.isEmpty()) {
            JavaCodeUnit unit = pending.pop();
            if (!seen.add(unit)) {
                continue;
            }
            for (JavaMethodCall call : unit.getMethodCallsFromSelf()) {
                if (call.getTargetOwner().isAssignableTo(EmailSender.class)) {
                    return Optional.of(call);
                }
                call.getTarget().resolveMember().ifPresent(pending::push);
            }
            lambdaBodies(unit).forEach(pending::push);
        }
        return Optional.empty();
    }

    /** The methods of {@code unit}'s class that {@code unit} binds as a lambda or method reference. */
    private static Set<JavaCodeUnit> lambdaBodies(JavaCodeUnit unit) {
        JavaClass owner = unit.getOwner();
        if (owner.getSource().isEmpty()) {
            return Set.of();
        }
        String ownerDescriptor = "L" + owner.getName().replace('.', '/') + ";";
        Set<String> names = new HashSet<>();
        for (MethodModel method : ClassFile.of().parse(ArchitectureRulesTest.classBytes(owner)).methods()) {
            if (!method.methodName().equalsString(unit.getName())) {
                continue;
            }
            method.code().ifPresent(code -> code.forEach(element -> {
                if (element instanceof InvokeDynamicInstruction indy) {
                    for (ConstantDesc argument : indy.bootstrapArgs()) {
                        if (argument instanceof DirectMethodHandleDesc handle
                                && handle.owner().descriptorString().equals(ownerDescriptor)) {
                            names.add(handle.methodName());
                        }
                    }
                }
            }));
        }
        Set<JavaCodeUnit> bodies = new HashSet<>();
        owner.getCodeUnits().stream().filter(candidate -> names.contains(candidate.getName())).forEach(bodies::add);
        return bodies;
    }

    private static boolean isLiveClient(JavaClass type, Set<String> liveClients) {
        return liveClients.stream().anyMatch(type::isAssignableTo);
    }

    private static boolean isStereotype(JavaClass javaClass) {
        return javaClass.isAnnotatedWith(Component.class) || javaClass.isMetaAnnotatedWith(Component.class);
    }

    /** Conditional on one of {@link #GUARDED_MODES}, with its live value. */
    private static boolean guarded(HasAnnotations<?> annotated) {
        return annotated.tryGetAnnotationOfType(ConditionalOnProperty.class.getName())
                .map(UploadAndEmailRulesTest::guardedMode)
                .orElse(false);
    }

    private static boolean guardedMode(JavaAnnotation<?> condition) {
        String prefix = condition.get("prefix").map(Object::toString).orElse("");
        String value = condition.get("havingValue").map(Object::toString).orElse("");
        for (String key : Set.of("name", "value")) {
            Object names = condition.get(key).orElse(new String[0]);
            for (String name : (String[]) names) {
                String property = prefix.isEmpty() ? name : prefix + "." + name;
                if (value.equals(GUARDED_MODES.get(property))) {
                    return true;
                }
            }
        }
        return false;
    }
}
