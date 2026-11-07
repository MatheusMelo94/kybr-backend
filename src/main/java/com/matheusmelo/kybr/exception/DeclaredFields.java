package com.matheusmelo.kybr.exception;

import jakarta.validation.Constraint;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import tools.jackson.databind.PropertyNamingStrategies;

/**
 * What a request body type declares about its fields, for error reporting: the declared order (so
 * the first invalid field in that order is reported), and the code a field's constraints raise (so
 * a wrongly typed value reports its field's own code, not a generic one).
 */
final class DeclaredFields {

    private DeclaredFields() {
    }

    /** The field's position in the type's declaration (record components, else fields); unknown last. */
    static int index(Class<?> type, String javaPath) {
        if (type == null || javaPath == null) {
            return Integer.MAX_VALUE;
        }
        String top = javaPath.split("[.\\[]", 2)[0];
        List<String> names = names(type);
        int index = names.indexOf(top);
        return index < 0 ? Integer.MAX_VALUE : index;
    }

    /**
     * The first code other than {@code VALOR_INVALIDO} raised by a constraint on the field with this
     * wire (snake_case) name, if any.
     */
    static Optional<ErrorCode> code(Class<?> type, String wireName) {
        if (type == null || wireName == null) {
            return Optional.empty();
        }
        return elements(type, wireName).stream()
                .flatMap(element -> Arrays.stream(element.getAnnotations()))
                .filter(annotation -> annotation.annotationType().isAnnotationPresent(Constraint.class))
                .map(DeclaredFields::template)
                .map(GlobalExceptionHandler::codeForTemplate)
                .filter(code -> code != ErrorCode.VALOR_INVALIDO)
                .findFirst();
    }

    private static List<String> names(Class<?> type) {
        if (type.isRecord()) {
            return Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).toList();
        }
        return Arrays.stream(type.getDeclaredFields()).map(Field::getName).toList();
    }

    private static List<AnnotatedElement> elements(Class<?> type, String wireName) {
        List<AnnotatedElement> elements = new ArrayList<>();
        for (String name : names(type)) {
            if (!PropertyNamingStrategies.SNAKE_CASE.nameForField(null, null, name).equals(wireName)) {
                continue;
            }
            if (type.isRecord()) {
                Arrays.stream(type.getRecordComponents()).filter(c -> c.getName().equals(name)).forEach(c -> {
                    elements.add(c);
                    elements.add(c.getAccessor());
                });
            }
            Arrays.stream(type.getDeclaredFields()).filter(f -> f.getName().equals(name)).forEach(elements::add);
        }
        return elements;
    }

    private static String template(Annotation annotation) {
        try {
            Method message = annotation.annotationType().getMethod("message");
            return String.valueOf(message.invoke(annotation));
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }
}
