package com.freemaf.agent;
/**
 * Инъекция применения размера окна.
 * Возвращает true при успешном применении и верификации,
 * false при неуспехе/откате.
 */
@FunctionalInterface
public interface SizeApplier {
    boolean apply(int width, int height);
}
