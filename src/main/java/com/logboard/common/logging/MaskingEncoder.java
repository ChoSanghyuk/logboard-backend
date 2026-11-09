package com.logboard.common.logging;

import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.encoder.Encoder;
import ch.qos.logback.core.encoder.EncoderBase;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.springframework.boot.logging.logback.StructuredLogEncoder;

/**
 * Console encoder (logback-spring.xml): encodes with Spring Boot's own encoder, the pattern one, or
 * {@link StructuredLogEncoder} when {@code logging.structured.format.console} is set (e.g. {@code ecs}), then masks
 * secrets in the result with {@link SecretMasker}. Covers message, exception and every structured field.
 */
public class MaskingEncoder extends EncoderBase<ILoggingEvent> {

    private String pattern;
    private String structuredFormat;
    private Charset charset = StandardCharsets.UTF_8;
    private Encoder<ILoggingEvent> delegate;

    public void setPattern(String pattern) {
        this.pattern = pattern;
    }

    public void setStructuredFormat(String structuredFormat) {
        this.structuredFormat = structuredFormat;
    }

    public void setCharset(Charset charset) {
        if (charset != null) {
            this.charset = charset;
        }
    }

    @Override
    public void start() {
        if (structuredFormat != null && !structuredFormat.isBlank()) {
            StructuredLogEncoder structured = new StructuredLogEncoder();
            structured.setFormat(structuredFormat.strip());
            structured.setCharset(charset);
            delegate = structured;
        } else {
            PatternLayoutEncoder plain = new PatternLayoutEncoder();
            plain.setPattern(pattern);
            plain.setCharset(charset);
            delegate = plain;
        }
        delegate.setContext(getContext());
        delegate.start();
        super.start();
    }

    @Override
    public void stop() {
        if (delegate != null) {
            delegate.stop();
        }
        super.stop();
    }

    @Override
    public byte[] headerBytes() {
        return delegate.headerBytes();
    }

    @Override
    public byte[] encode(ILoggingEvent event) {
        byte[] encoded = delegate.encode(event);
        if (encoded == null || encoded.length == 0) {
            return encoded;
        }
        String text = new String(encoded, charset);
        String masked = SecretMasker.mask(text);
        return masked.equals(text) ? encoded : masked.getBytes(charset);
    }

    @Override
    public byte[] footerBytes() {
        return delegate.footerBytes();
    }
}
