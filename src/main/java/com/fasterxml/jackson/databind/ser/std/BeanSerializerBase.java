package com.fasterxml.jackson.databind.ser.std;

import java.io.IOException;
import java.lang.reflect.Type;

import com.fasterxml.jackson.core.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.introspect.AnnotatedMember;
import com.fasterxml.jackson.databind.jsonschema.JsonSchema;
import com.fasterxml.jackson.databind.jsonschema.SchemaAware;
import com.fasterxml.jackson.databind.jsontype.TypeSerializer;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ser.*;
import com.fasterxml.jackson.databind.ser.impl.ObjectIdHandler;
import com.fasterxml.jackson.databind.util.NameTransformer;

/**
 * Base class both for the standard bean serializer, and couple
 * of variants that only differ in small details.
 * Can be used for custom bean serializers as well, although that
 * is not the primary design goal.
 */
public abstract class BeanSerializerBase
    extends StdSerializer<Object>
    implements ResolvableSerializer, SchemaAware
{
    final protected static BeanPropertyWriter[] NO_PROPS = new BeanPropertyWriter[0];

    /*
    /**********************************************************
    /* Configuration
    /**********************************************************
     */

    /**
     * Writers used for outputting actual property values
     */
    final protected BeanPropertyWriter[] _props;

    /**
     * Optional filters used to suppress output of properties that
     * are only to be included in certain views
     */
    final protected BeanPropertyWriter[] _filteredProps;

    /**
     * Handler for {@link com.fasterxml.jackson.annotation.JsonAnyGetter}
     * annotated properties
     */
    final protected AnyGetterWriter _anyGetterWriter;

    /**
     * If this POJO can be alternatively serialized using just an object id
     * to denote a reference to previously serialized object,
     * this Object will handle details.
     */
    final protected ObjectIdHandler _objectIdHandler;
    
    /**
     * If using custom type ids (usually via getter, or field), this is the
     * reference to that member.
     */
    final protected AnnotatedMember _typeId;
    
    /**
     * Id of the bean property filter to use, if any; null if none.
     */
    final protected Object _propertyFilterId;
    
    /*
    /**********************************************************
    /* Life-cycle: constructors
    /**********************************************************
     */

    /**
     * Constructor used by {@link BeanSerializerBuilder} to create an
     * instance
     * 
     * @param type Nominal type of values handled by this serializer
     * @param builder Builder for accessing other collected information
     */
    protected BeanSerializerBase(JavaType type, BeanSerializerBuilder builder,
            BeanPropertyWriter[] properties, BeanPropertyWriter[] filteredProperties)
    {
        super(type);
        _props = properties;
        _filteredProps = filteredProperties;
        if (builder == null) { // mostly for testing
            _objectIdHandler = null;
            _typeId = null;
            _propertyFilterId = null;
            _anyGetterWriter = null;
        } else {
            _anyGetterWriter = builder.getAnyGetter();
            _typeId = builder.getTypeId();
            _propertyFilterId = builder.getFilterId();
            // need to create a property placeholder for object id:
            AnnotatedMember objectIdAcc = builder.getObjectId();
            if (objectIdAcc == null) {
                _objectIdHandler = null;
            } else {
                BeanDescription beanDesc = builder.getBeanDescription();
                AnnotatedMember oidMember = builder.getObjectId();
                JavaType oidType = beanDesc.resolveType(oidMember.getGenericType());
                BeanProperty.Std prop = new BeanProperty.Std(oidMember.getName(),
                        oidType, beanDesc.getClassAnnotations(), oidMember);
                _objectIdHandler = ObjectIdHandler.construct(prop);
            }
        }
    }

    public BeanSerializerBase(BeanSerializerBase src,
            BeanPropertyWriter[] properties, BeanPropertyWriter[] filteredProperties)
    {
        super(src._handledType);
        _props = properties;
        _filteredProps = filteredProperties;
        
        _anyGetterWriter = src._anyGetterWriter;
        _objectIdHandler = src._objectIdHandler;
        _typeId = src._typeId;
        _propertyFilterId = src._propertyFilterId;
    }

    /**
     * Copy-constructor that is useful for sub-classes that just want to
     * copy all super-class properties without modifications.
     */
    protected BeanSerializerBase(BeanSerializerBase src) {
        this(src, src._props, src._filteredProps);
    }

    /**
     * Copy-constructor that will also rename properties with given prefix
     * (if it's non-empty)
     */
    protected BeanSerializerBase(BeanSerializerBase src, NameTransformer unwrapper) {
        this(src, rename(src._props, unwrapper), rename(src._filteredProps, unwrapper));
    }

    private final static BeanPropertyWriter[] rename(BeanPropertyWriter[] props,
            NameTransformer transformer)
    {
        if (props == null || props.length == 0 || transformer == null || transformer == NameTransformer.NOP) {
            return props;
        }
        final int len = props.length;
        BeanPropertyWriter[] result = new BeanPropertyWriter[len];
        for (int i = 0; i < len; ++i) {
            BeanPropertyWriter bpw = props[i];
            if (bpw != null) {
                result[i] = bpw.rename(transformer);
            }
        }
        return result;
    }
    
    /*
    /**********************************************************
    /* Partial JsonSerializer implementation
    /**********************************************************
     */

    // Main serialization method left unimplemented
    @Override
    public abstract void serialize(Object bean, JsonGenerator jgen, SerializerProvider provider)
        throws IOException, JsonGenerationException;

    // Type-info-augmented case implemented as it does not usually differ between impls
    @Override
    public void serializeWithType(Object bean, JsonGenerator jgen, SerializerProvider provider,
            TypeSerializer typeSer)
        throws IOException, JsonGenerationException
    {
        if (_typeId != null) {
            serializeWithCustomType(bean, jgen, provider, typeSer);
            return;
        }
        typeSer.writeTypePrefixForObject(bean, jgen);
        if (_propertyFilterId != null) {
            serializeFieldsFiltered(bean, jgen, provider);
        } else {
            serializeFields(bean, jgen, provider);
        }
        typeSer.writeTypeSuffixForObject(bean, jgen);
    }

    private final void serializeWithCustomType(Object bean,
            JsonGenerator jgen, SerializerProvider provider,
            TypeSerializer typeSer)
        throws IOException, JsonGenerationException
    {
        final Object typeId = _typeId.getValue(bean);
        String typeStr;
        if (typeId == null) {
            typeStr = "";
        } else {
            typeStr = (typeId instanceof String) ? (String) typeId : typeId.toString();
        }
        typeSer.writeCustomTypePrefixForObject(bean, jgen, typeStr);
        if (_propertyFilterId != null) {
            serializeFieldsFiltered(bean, jgen, provider);
        } else {
            serializeFields(bean, jgen, provider);
        }
        typeSer.writeCustomTypeSuffixForObject(bean, jgen, typeStr);
    }
    
    /*
    /**********************************************************
    /* Field serialization methods
    /**********************************************************
     */
    
    protected void serializeFields(Object bean, JsonGenerator jgen, SerializerProvider provider)
        throws IOException, JsonGenerationException
    {
        final BeanPropertyWriter[] props;
        if (_filteredProps != null && provider.getSerializationView() != null) {
            props = _filteredProps;
        } else {
            props = _props;
        }
        int i = 0;
        try {
            for (final int len = props.length; i < len; ++i) {
                BeanPropertyWriter prop = props[i];
                if (prop != null) { // can have nulls in filtered list
                    prop.serializeAsField(bean, jgen, provider);
                }
            }
            if (_anyGetterWriter != null) {
                _anyGetterWriter.getAndSerialize(bean, jgen, provider);
            }
        } catch (Exception e) {
            String name = (i == props.length) ? "[anySetter]" : props[i].getName();
            wrapAndThrow(provider, e, bean, name);
        } catch (StackOverflowError e) {
            /* 04-Sep-2009, tatu: Dealing with this is tricky, since we do not
             *   have many stack frames to spare... just one or two; can't
             *   make many calls.
             */
            JsonMappingException mapE = new JsonMappingException("Infinite recursion (StackOverflowError)");
            String name = (i == props.length) ? "[anySetter]" : props[i].getName();
            mapE.prependPath(new JsonMappingException.Reference(bean, name));
            throw mapE;
        }
    }

    /**
     * Alternative serialization method that gets called when there is a
     * {@link BeanPropertyFilter} that needs to be called to determine
     * which properties are to be serialized (and possibly how)
     */
    protected void serializeFieldsFiltered(Object bean, JsonGenerator jgen, SerializerProvider provider)
        throws IOException, JsonGenerationException
    {
        /* note: almost verbatim copy of "serializeFields"; copied (instead of merged)
         * so that old method need not add check for existence of filter.
         */
        
        final BeanPropertyWriter[] props;
        if (_filteredProps != null && provider.getSerializationView() != null) {
            props = _filteredProps;
        } else {
            props = _props;
        }
        final BeanPropertyFilter filter = findFilter(provider);
        // better also allow missing filter actually..
        if (filter == null) {
            serializeFields(bean, jgen, provider);
            return;
        }
        
        int i = 0;
        try {
            for (final int len = props.length; i < len; ++i) {
                BeanPropertyWriter prop = props[i];
                if (prop != null) { // can have nulls in filtered list
                    filter.serializeAsField(bean, jgen, provider, prop);
                }
            }
            if (_anyGetterWriter != null) {
                _anyGetterWriter.getAndSerialize(bean, jgen, provider);
            }
        } catch (Exception e) {
            String name = (i == props.length) ? "[anySetter]" : props[i].getName();
            wrapAndThrow(provider, e, bean, name);
        } catch (StackOverflowError e) {
            JsonMappingException mapE = new JsonMappingException("Infinite recursion (StackOverflowError)");
            String name = (i == props.length) ? "[anySetter]" : props[i].getName();
            mapE.prependPath(new JsonMappingException.Reference(bean, name));
            throw mapE;
        }
    }

    /**
     * Helper method used to locate filter that is needed, based on filter id
     * this serializer was constructed with.
     */
    protected BeanPropertyFilter findFilter(SerializerProvider provider)
        throws JsonMappingException
    {
        final Object filterId = _propertyFilterId;
        FilterProvider filters = provider.getFilterProvider();
        // Not ok to miss the provider, if a filter is declared to be needed.
        if (filters == null) {
            throw new JsonMappingException("Can not resolve BeanPropertyFilter with id '"+filterId+"'; no FilterProvider configured");
        }
        BeanPropertyFilter filter = filters.findFilter(filterId);
        // But whether unknown ids are ok just depends on filter provider; if we get null that's fine
        return filter;
    }
    
    @Override
    public JsonNode getSchema(SerializerProvider provider, Type typeHint)
        throws JsonMappingException
    {
        ObjectNode o = createSchemaNode("object", true);
        //todo: should the classname go in the title?
        //o.put("title", _className);
        ObjectNode propertiesNode = o.objectNode();
        for (int i = 0; i < _props.length; i++) {
            BeanPropertyWriter prop = _props[i];
            JavaType propType = prop.getSerializationType();
            // 03-Dec-2010, tatu: SchemaAware REALLY should use JavaType, but alas it doesn't...
            Type hint = (propType == null) ? prop.getGenericPropertyType() : propType.getRawClass();
            // Maybe it already has annotated/statically configured serializer?
            JsonSerializer<Object> ser = prop.getSerializer();
            if (ser == null) { // nope
                Class<?> serType = prop.getRawSerializationType();
                if (serType == null) {
                    serType = prop.getPropertyType();
                }
                ser = provider.findValueSerializer(serType, prop);
            }
            JsonNode schemaNode = (ser instanceof SchemaAware) ?
                    ((SchemaAware) ser).getSchema(provider, hint) : 
                    JsonSchema.getDefaultSchemaNode();
            propertiesNode.put(prop.getName(), schemaNode);
        }
        o.put("properties", propertiesNode);
        return o;
    }

    /*
    /**********************************************************
    /* ResolvableSerializer impl
    /**********************************************************
     */

    @Override
    public void resolve(SerializerProvider provider)
        throws JsonMappingException
    {
        int filteredCount = (_filteredProps == null) ? 0 : _filteredProps.length;
        for (int i = 0, len = _props.length; i < len; ++i) {
            BeanPropertyWriter prop = _props[i];
            // let's start with null serializer resolution actually
            if (!prop.willSuppressNulls() && !prop.hasNullSerializer()) {
                JsonSerializer<Object> nullSer = provider.findNullValueSerializer(prop);
                if (nullSer != null) {
                    prop.assignNullSerializer(nullSer);
                    // also: remember to replace filtered property too? (see [JACKSON-364])
                    if (i < filteredCount) {
                        BeanPropertyWriter w2 = _filteredProps[i];
                        if (w2 != null) {
                            w2.assignNullSerializer(nullSer);
                        }
                    }
                }
            }
            
            if (prop.hasSerializer()) {
                continue;
            }
            // Was the serialization type hard-coded? If so, use it
            JavaType type = prop.getSerializationType();
            
            /* It not, we can use declared return type if and only if
             * declared type is final -- if not, we don't really know
             * the actual type until we get the instance.
             */
            if (type == null) {
                type = provider.constructType(prop.getGenericPropertyType());
                if (!type.isFinal()) {
                    /* 18-Feb-2010, tatus: But even if it is non-final, we may
                     *   need to retain some of type information so that we can
                     *   accurately handle contained types
                     */
                    if (type.isContainerType() || type.containedTypeCount() > 0) {
                        prop.setNonTrivialBaseType(type);
                    }
                    continue;
                }
            }
            
            JsonSerializer<Object> ser = provider.findValueSerializer(type, prop);
            /* 04-Feb-2010, tatu: We may have stashed type serializer for content types
             *   too, earlier; if so, it's time to connect the dots here:
             */
            if (type.isContainerType()) {
                TypeSerializer typeSer = type.getContentType().getTypeHandler();
                if (typeSer != null) {
                    // for now, can do this only for standard containers...
                    if (ser instanceof ContainerSerializer<?>) {
                        // ugly casts... but necessary
                        @SuppressWarnings("unchecked")
                        JsonSerializer<Object> ser2 = (JsonSerializer<Object>)((ContainerSerializer<?>) ser).withValueTypeSerializer(typeSer);
                        ser = ser2;
                    }
                }
            }
            prop.assignSerializer(ser);
            // and maybe replace filtered property too? (see [JACKSON-364])
            if (i < filteredCount) {
                BeanPropertyWriter w2 = _filteredProps[i];
                if (w2 != null) {
                    w2.assignSerializer(ser);
                }
            }
        }

        // also, any-getter may need to be resolved
        if (_anyGetterWriter != null) {
            _anyGetterWriter.resolve(provider);
        }
    }

}
