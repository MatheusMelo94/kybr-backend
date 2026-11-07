/**
 * The management server (internal port, never published): its deny-by-default security chain,
 * the admin bearer token, its own request-id, body-cap and access-line filters, and the startup
 * guard that keeps the admin endpoint off the public port.
 */
package com.matheusmelo.kybr.config.management;
