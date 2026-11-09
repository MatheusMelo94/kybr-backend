/**
 * Seller documents: the stored files and the document requests that let a seller send more.
 * Documents use snake_case field names. Enums whose constants are lower case are stored and
 * exchanged by their exact names, which are the wire values.
 *
 * <p>Nothing here ever holds what the seller called a file: stored objects get generated keys,
 * and a file's type comes from its first bytes.
 */
package com.matheusmelo.kybr.model.document;
