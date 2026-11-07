package com.matheusmelo.kybr.util;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Matches the responsible person a seller named against a company's partner (QSA) names, pure and
 * stateless. Names are compared after {@link NameNormalizer}.
 *
 * <ul>
 *   <li>{@link Match#EXACT}: the normalised names are equal.
 *   <li>{@link Match#PARTIAL}: every token of the shorter name appears in the other, or the first and
 *       last tokens are the same.
 *   <li>{@link Match#NONE}: neither.
 * </ul>
 *
 * <p>The best match over all partners wins. A micro-entrepreneur has no partner list: the person's
 * name is the company name with its CPF (masked or not) removed ({@link #matchMei}).
 */
public final class QsaMatcher {

    /** How well the responsible person matches; declared best first. */
    public enum Match { EXACT, PARTIAL, NONE }

    private QsaMatcher() {
    }

    public static Match match(String responsavel, List<String> partners) {
        Match best = Match.NONE;
        for (String partner : partners) {
            Match match = compare(responsavel, partner);
            if (match.compareTo(best) < 0) {
                best = match;
            }
        }
        return best;
    }

    /** A micro-entrepreneur: compared with the name in the company name, its CPF removed. */
    public static Match matchMei(String responsavel, String razaoSocial) {
        String name = CpfMasker.mask(razaoSocial == null ? "" : razaoSocial).replace(CpfMasker.MASK, " ");
        return compare(responsavel, name);
    }

    private static Match compare(String typed, String registered) {
        String a = NameNormalizer.normalize(typed);
        String b = NameNormalizer.normalize(registered);
        if (a.isEmpty() || b.isEmpty()) {
            return Match.NONE;
        }
        if (a.equals(b)) {
            return Match.EXACT;
        }
        List<String> left = Arrays.asList(a.split(" "));
        List<String> right = Arrays.asList(b.split(" "));
        List<String> shorter = left.size() <= right.size() ? left : right;
        Set<String> longer = (shorter == left ? right : left).stream().collect(Collectors.toSet());
        boolean allTokens = longer.containsAll(shorter);
        boolean firstAndLast = left.getFirst().equals(right.getFirst()) && left.getLast().equals(right.getLast());
        return allTokens || firstAndLast ? Match.PARTIAL : Match.NONE;
    }
}
