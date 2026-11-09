package com.logboard.common;

import io.swagger.v3.oas.models.media.JsonSchema;
import io.swagger.v3.oas.models.media.Schema;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.stereotype.Component;

/**
 * The API sends explicit {@code null} for unset optional fields, so every property that is not {@code required}
 * is marked nullable (OpenAPI 3.1): {@code type: [T, "null"]} (enums also list {@code null}), and a {@code $ref}
 * becomes {@code oneOf: [{$ref}, {type: "null"}]}. Never-null fields must be marked REQUIRED on the DTOs.
 * <p>Response fields are always present (sent as {@code null}, never omitted), so in every schema that is not a
 * request body all properties are listed in {@code required}: nullable response fields become required + nullable.
 * Request schemas keep their optional fields optional.
 */
@Component
public class OpenApiNullableCustomizer implements OpenApiCustomizer {

    /** Optional but never null. */
    private static final Set<String> NEVER_NULL = Set.of("ApiError.details");

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void customise(io.swagger.v3.oas.models.OpenAPI openApi) {
        if (openApi.getComponents() == null || openApi.getComponents().getSchemas() == null) {
            return;
        }
        Set<String> requestSchemas = requestSchemas(openApi);
        openApi.getComponents().getSchemas().forEach((name, schema) -> {
            Map<String, Schema> props = schema.getProperties();
            if (props == null) {
                return;
            }
            List<String> required = schema.getRequired() != null ? schema.getRequired() : List.of();
            props.replaceAll((prop, p) -> required.contains(prop) || NEVER_NULL.contains(name + "." + prop)
                    ? p : nullable(p));
            if (!requestSchemas.contains(name)) {
                props.keySet().stream()
                        .filter(prop -> !required.contains(prop) && !NEVER_NULL.contains(name + "." + prop))
                        .forEach(schema::addRequiredItem);
            }
        });
    }

    /**
     * Names of component schemas used directly as a request body. Assumes request DTOs have no nested DTO refs and
     * are never also used as a response: revisit this if either changes.
     */
    private static Set<String> requestSchemas(io.swagger.v3.oas.models.OpenAPI openApi) {
        Set<String> names = new LinkedHashSet<>();
        if (openApi.getPaths() == null) {
            return names;
        }
        openApi.getPaths().values().forEach(path -> path.readOperations().forEach(op -> {
            if (op.getRequestBody() != null && op.getRequestBody().getContent() != null) {
                op.getRequestBody().getContent().values().forEach(media -> {
                    if (media.getSchema() != null && media.getSchema().get$ref() != null) {
                        String ref = media.getSchema().get$ref();
                        names.add(ref.substring(ref.lastIndexOf('/') + 1));
                    }
                });
            }
        }));
        return names;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Schema nullable(Schema p) {
        if (p.get$ref() != null) {
            Schema wrapper = new JsonSchema().oneOf(List.of(new JsonSchema().$ref(p.get$ref()),
                    new JsonSchema().types(Set.of("null"))));
            wrapper.setDescription(p.getDescription());
            return wrapper;
        }
        Set<String> types = new LinkedHashSet<>();
        if (p.getTypes() != null) {
            types.addAll(p.getTypes());
        } else if (p.getType() != null) {
            types.add(p.getType());
        }
        types.add("null");
        p.setTypes(types);
        if (p.getEnum() != null && !p.getEnum().contains(null)) {
            List values = new ArrayList<>(p.getEnum());
            values.add(null);
            p.setEnum(values);
        }
        return p;
    }
}
