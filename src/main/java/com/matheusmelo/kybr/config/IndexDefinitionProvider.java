package com.matheusmelo.kybr.config;

import java.util.List;
import org.springframework.data.mongodb.core.index.IndexDefinition;

/**
 * Declares the indexes one module needs on one collection. Every provider bean is applied by
 * {@link IndexBootstrap} at startup ({@code auto-index-creation} is off).
 */
public interface IndexDefinitionProvider {

    String collection();

    List<IndexDefinition> indexes();
}
