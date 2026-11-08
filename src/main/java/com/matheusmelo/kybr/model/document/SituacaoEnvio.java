package com.matheusmelo.kybr.model.document;

/**
 * A document type's verification state as the seller sees it on D4: {@code verificando} while any of
 * its files is still being checked, then {@code recusado} when its latest upload had a refused file,
 * otherwise {@code aceito}.
 */
public enum SituacaoEnvio {
    verificando,
    aceito,
    recusado
}
