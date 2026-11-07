/**
 * Seller-link records: cases, sellers, verifications and their audit, outbox, cache and credit
 * documents. Documents use snake_case field names. Enums whose constants are lower case are
 * stored and exchanged by their exact names, which are the wire values.
 *
 * <p>Seller-supplied text ({@code responsavel}, {@code ref}, {@code ext_id}) and the provider's
 * {@code razao_social} are untrusted on output: HTML-encode them in a page, escape formulas in a
 * CSV, and never interpolate them raw into an e-mail.
 */
package com.matheusmelo.kybr.model.link;
