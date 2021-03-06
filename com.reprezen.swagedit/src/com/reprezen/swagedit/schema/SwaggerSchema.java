/*******************************************************************************
 * Copyright (c) 2016 ModelSolv, Inc. and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    ModelSolv, Inc. - initial API and implementation and/or initial documentation
 *******************************************************************************/
package com.reprezen.swagedit.schema;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Strings;
import com.reprezen.swagedit.json.references.JsonReference;
import com.reprezen.swagedit.model.AbstractNode;

/**
 * Represents the Swagger Schema.
 * 
 */
public class SwaggerSchema {

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, JsonSchema> schemas = new HashMap<>();

    private JsonSchema swaggerType;
    private JsonSchema coreType;

    public SwaggerSchema() {
        init();
    }

    protected class JsonSchema {

        private final Map<JsonPointer, TypeDefinition> types = new HashMap<>();
        private final String id;
        private final JsonNode content;
        private final SwaggerSchema manager;

        private ObjectTypeDefinition type;

        public JsonSchema(JsonNode content, SwaggerSchema manager) {
            this.id = content.get("id").asText().replaceAll("#", "");
            this.content = content;
            this.manager = manager;
            this.manager.schemas.put(id, this);
        }

        public String getId() {
            return id;
        }

        public SwaggerSchema getManager() {
            return manager;
        }

        void setType(ObjectTypeDefinition type) {
            this.type = type;
        }

        public ObjectTypeDefinition getType() {
            return type;
        }

        /**
         * Creates a new type after resolving it from the pointer.
         * 
         * @param pointer
         * @return type
         */
        protected TypeDefinition createType(JsonPointer pointer) {
            final JsonNode definition = content.at(pointer);
            if (definition == null || definition.isMissingNode()) {
                return null;
            }

            TypeDefinition typeDef;
            if (JsonReference.isReference(definition)) {
                typeDef = new ReferenceTypeDefinition(this, pointer, definition);
            } else {
                final JsonType type = JsonType.valueOf(definition);
                switch (type) {
                case OBJECT:
                    typeDef = new ObjectTypeDefinition(this, pointer, definition);
                    break;
                case ARRAY:
                    typeDef = new ArrayTypeDefinition(this, pointer, definition);
                    break;
                case ALL_OF:
                case ANY_OF:
                case ONE_OF:
                    typeDef = new ComplexTypeDefinition(this, pointer, definition, type);
                    break;
                default:
                    typeDef = new TypeDefinition(this, pointer, definition, type);
                }
            }
            types.put(pointer, typeDef);
            return typeDef;
        }

        protected TypeDefinition createType(TypeDefinition parent, String property, JsonNode definition) {
            return createType(JsonPointer.compile(parent.getPointer() + "/" + property));
        }

        public JsonNode resolve(JsonPointer pointer) {
            return content.at(pointer);
        }

        public TypeDefinition get(JsonPointer pointer) {
            if (pointer == null || Strings.emptyToNull(pointer.toString()) == null) {
                return type;
            }
            return types.get(pointer);
        }

    }

    protected void init() {
        JsonNode core;
        try {
            core = mapper.readTree(getClass().getResourceAsStream("core.json"));
        } catch (IOException e) {
            return;
        }

        JsonNode content;
        try {
            content = mapper.readTree(getClass().getResourceAsStream("schema.json"));
        } catch (IOException e) {
            return;
        }

        coreType = new JsonSchema(core, this);
        coreType.setType(new ObjectTypeDefinition(coreType, JsonPointer.compile(""), core));

        swaggerType = new JsonSchema(content, this);
        swaggerType.setType(new ObjectTypeDefinition(swaggerType, JsonPointer.compile(""), content));
    }

    /**
     * Returns the content of the schema as JSON.
     * 
     * @return schema content
     */
    public JsonNode asJson() {
        return swaggerType.getType().asJson();
    }

    /**
     * Returns the type of a node.
     * 
     * <br/>
     * 
     * Note: this method should be used only during initialization of a model.
     * 
     * @param node
     * @return node's type
     */
    public TypeDefinition getType(AbstractNode node) {
        JsonPointer pointer = node.getPointer();

        if (JsonPointer.compile("").equals(pointer)) {
            return swaggerType.getType();
        }

        String[] paths = pointer.toString().substring(1).split("/");
        TypeDefinition current = swaggerType.getType();

        if (current != null) {
            for (String property : paths) {
                TypeDefinition next = current.getPropertyType(property);
                // not found, we stop here
                if (next == null) {
                    break;
                }
                current = next;
            }
        }

        return current;
    }

    /**
     * Returns the schema root type.
     * 
     * @return root type
     */
    public TypeDefinition getRootType() {
        return swaggerType.getType();
    }

    /**
     * Returns the type that is reachable by the given pointer inside the JSON schema. <br/>
     * 
     * Examples of pointers: <br/>
     * - /properties/swagger <br/>
     * - /definitions/paths
     * 
     * @param pointer
     * @return type
     */
    public TypeDefinition getType(String reference) {
        if (Strings.emptyToNull(reference) == null) {
            return swaggerType.getType();
        }

        JsonPointer pointer = pointer(reference);
        JsonSchema schema = getSchema(baseURI(reference));

        return schema.get(pointer);
    }

    public ObjectMapper getMapper() {
        return mapper;
    }

    /**
     * Returns the type definition reachable by the JSON reference. The context type is used to identify the schema that
     * will be used to resolve the referenced type.
     * 
     * @param context
     * @param reference
     * @return type
     */
    public TypeDefinition resolve(TypeDefinition context, String reference) {
        String schemaId = baseURI(reference);
        JsonPointer pointer = pointer(reference);
        JsonSchema schema = schemaId == null ? context.getSchema() : getSchema(schemaId);

        if (pointer == null) {
            return schema.getType();
        }
        return schema.get(pointer);
    }

    protected JsonSchema getSchema(String id) {
        return Strings.emptyToNull(id) == null ? swaggerType : schemas.get(id);
    }

    protected String baseURI(String href) {
        if (Strings.emptyToNull(href) == null || href.startsWith("/")) {
            return null;
        }

        return href.startsWith("#") ? null : href.split("#")[0];
    }

    protected JsonPointer pointer(String href) {
        if (href.startsWith("#")) {
            return JsonPointer.compile(href.substring(1));
        } else if (href.startsWith("/")) {
            return JsonPointer.compile(href);
        } else {
            String[] split = href.split("#");
            return split.length > 1 ? JsonPointer.compile(split[1]) : null;
        }
    }

}
