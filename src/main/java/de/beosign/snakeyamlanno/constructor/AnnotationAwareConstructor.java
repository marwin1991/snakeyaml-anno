package de.beosign.snakeyamlanno.constructor;

import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.apache.commons.lang3.ClassUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.TypeDescription;
import org.yaml.snakeyaml.constructor.Construct;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.introspector.Property;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeId;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;

import de.beosign.snakeyamlanno.AnnotationAwarePropertyUtils;
import de.beosign.snakeyamlanno.annotation.Type;
import de.beosign.snakeyamlanno.instantiator.InstantiateBy;
import de.beosign.snakeyamlanno.instantiator.Instantiator;
import de.beosign.snakeyamlanno.type.NoSubstitutionTypeSelector;
import de.beosign.snakeyamlanno.type.SubstitutionTypeSelector;

/**
 * Needed for implementing the auto type detection feature.
 * 
 * @author florian
 */
public class AnnotationAwareConstructor extends Constructor {
    private static final Logger log = LoggerFactory.getLogger(AnnotationAwareConstructor.class);

    private Map<Class<?>, Type> typesMap = new HashMap<>();
    private Map<Class<?>, ConstructBy> constructByMap = new HashMap<>();
    private Map<Class<?>, InstantiateBy> instantiateByMap = new HashMap<>();
    private IdentityHashMap<Node, Property> nodeToPropertyMap = new IdentityHashMap<>();
    private Instantiator globalInstantiator;

    /**
     * Creates constructor.
     * 
     * @param theRoot root class - you can cast the result of the parsing process to this class
     */
    public AnnotationAwareConstructor(Class<?> theRoot) {
        this(theRoot, false);
    }

    /**
     * Creates constructor.
     * 
     * @param theRoot root class - you can cast the result of the parsing process to this class
     * @param caseInsensitive true if parsing should be independent of case of keys
     */
    public AnnotationAwareConstructor(Class<? extends Object> theRoot, boolean caseInsensitive) {
        super(theRoot);
        setPropertyUtils(new AnnotationAwarePropertyUtils(caseInsensitive));
        yamlClassConstructors.put(NodeId.mapping, new AnnotationAwareMappingConstructor());
        yamlClassConstructors.put(NodeId.scalar, new AnnotationAwareScalarConstructor());
    }

    /**
     * Sets a <i>global</i> instantiator that can be used to create instance for all types that the given instantiator wishes to consider. If the instantiator
     * wants to apply the default instantiation logic of SnakeYaml, it can return <code>null</code>.<br>
     * In order to apply an {@link Instantiator} for a given type only, or if you want to override the behaviour for a particular type, use the
     * {@link InstantiateBy} annotation or use the programmatic way (e.g. {@link #registerInstantiator(Class, Class)}.
     * 
     * @param globalInstantiator instantiator instance that is global to this Constructor.
     * @since 0.9.0
     */
    public void setGlobalInstantiator(Instantiator globalInstantiator) {
        this.globalInstantiator = globalInstantiator;
    }

    /**
     * Overridden to implement the "auto type detection" feature and the "instantiator" feature.
     */
    @Override
    protected Object newInstance(Class<?> ancestor, Node node, boolean tryDefault) throws InstantiationException {

        if (node instanceof MappingNode) {
            Type typeAnnotation = getTypeForClass(node.getType());
            MappingNode mappingNode = (MappingNode) node;
            Class<?> type = mappingNode.getType();

            if (typeAnnotation != null && typeAnnotation.substitutionTypes().length > 0) {
                // One or more substitution types have been defined
                List<Class<?>> validSubstitutionTypes = new ArrayList<>();
                SubstitutionTypeSelector substitutionTypeSelector = null;

                if (typeAnnotation.substitutionTypeSelector() != NoSubstitutionTypeSelector.class) {
                    try {
                        // check if default detection algorithm is to be applied
                        substitutionTypeSelector = typeAnnotation.substitutionTypeSelector().newInstance();
                        if (!substitutionTypeSelector.disableDefaultAlgorithm()) {
                            validSubstitutionTypes = getValidSubstitutionTypes(type, typeAnnotation, mappingNode.getValue());
                        }
                    } catch (InstantiationException | IllegalAccessException e) {
                        throw new YAMLException("Cannot instantiate substitutionTypeSelector of type " + typeAnnotation.substitutionTypeSelector().getName(),
                                e);
                    }
                } else {
                    validSubstitutionTypes = getValidSubstitutionTypes(type, typeAnnotation, mappingNode.getValue());
                }

                if (substitutionTypeSelector != null) {
                    node.setType(substitutionTypeSelector.getSelectedType(mappingNode, validSubstitutionTypes));
                    log.debug("Type = {}, using substitution type {} calculated by SubstitutionTypeSelector {}", type, node.getType(),
                            typeAnnotation.substitutionTypeSelector().getName());
                } else {
                    if (validSubstitutionTypes.size() == 0) {
                        log.warn("Type = {}, NO possible substitution types found, using default YAML algorithm", type);
                    } else {
                        if (validSubstitutionTypes.size() > 1) {
                            log.debug("Type = {}, using substitution types = {}, choosing first", type, validSubstitutionTypes);
                        } else {
                            log.trace("Type = {}, using substitution type = {}", type, validSubstitutionTypes.get(0));
                        }
                        node.setType(validSubstitutionTypes.get(0));
                    }
                }

            }
        }

        /*
         *  Create an instance using the following order:
         *  1. check node type for an instantiator registration and create one if present
         *  2. call instantiator; if return value is null, check global instantiator within this constructor if present
         *  3. call global instantiator; if return value is null, call super (default instantiation logic)
         */
        Instantiator defaultInstantiator = (nodeType, n, tryDef, anc, def) -> super.newInstance(anc, n, tryDef);
        Object instance = null;
        InstantiateBy instantiateBy = getInstantiateBy(node.getType());
        if (instantiateBy != null && !instantiateBy.value().equals(Instantiator.class)) {
            try {
                instance = instantiateBy.value().newInstance().createInstance(node.getType(), node, tryDefault, ancestor, defaultInstantiator);
            } catch (IllegalAccessException e) {
                throw new InstantiationException("Cannot access constructor of " + instantiateBy.value() + ": " + e.getMessage());
            }
        }

        if (instance == null && globalInstantiator != null) {
            instance = globalInstantiator.createInstance(node.getType(), node, tryDefault, ancestor, defaultInstantiator);
        }

        if (instance == null) {
            instance = super.newInstance(ancestor, node, tryDefault);
        }
        return instance;
    }

    /**
     * Returns the {@link Type} that is registered for the given class. If a type has been manually registered, this is returned. Otherwise, the
     * {@link Type} annotation on the given class is returned.
     * 
     * @param clazz class
     * @return {@link Type} for given type
     */
    protected Type getTypeForClass(Class<?> clazz) {
        Type typeAnnotation = clazz.getAnnotation(Type.class);

        return typesMap.getOrDefault(clazz, typeAnnotation);
    }

    /**
     * Constructs a singleton list from the constructed object unless the constructed object is <code>null</code>, in which case <code>null</code> is returned.
     * 
     * @param node node - a {@link MappingNode} or a {@link ScalarNode} that is to assigned to a collection property
     * @param defaultConstructor default constructor
     * @return a singleton list or <code>null</code>
     */
    protected List<?> constructNodeAsList(Node node, Function<Node, Object> defaultConstructor) {
        Class<?> origType = node.getType();
        Property propertyOfNode = nodeToPropertyMap.get(node);
        if (propertyOfNode.getActualTypeArguments() != null && propertyOfNode.getActualTypeArguments().length > 0) {
            node.setType(propertyOfNode.getActualTypeArguments()[0]);
        }
        Object singleObject = constructObject(node, defaultConstructor);
        node.setType(origType);
        if (singleObject == null) {
            return null;
        } else {
            return Collections.singletonList(singleObject);
        }
    }

    /**
     * Returns all <b>valid</b> substitution types from the list given by the {@link Type#substitutionTypes()} method. This method
     * helps implementing the "auto type detection" feature.
     * 
     * @param type type
     * @param typeAnnotation the {@link Type} annotation to use for the given class
     * @param nodeValue node values
     */
    private List<Class<?>> getValidSubstitutionTypes(Class<?> type, Type typeAnnotation, List<NodeTuple> nodeValue) {
        List<Class<?>> validSubstitutionTypes = new ArrayList<>();
        List<? extends Class<?>> substitutionTypeList = Arrays.asList(typeAnnotation.substitutionTypes());
        /*
         *  For each possible substitution type, check if all YAML properties match a Bean property.
         *  If this is the case, this subtype is a valid substitution
         */
        for (Class<?> substitutionType : substitutionTypeList) {
            boolean isValidType = true;
            for (NodeTuple tuple : nodeValue) {
                String key = null;
                try {
                    ScalarNode keyNode = getKeyNode(tuple);
                    key = (String) AnnotationAwareConstructor.this.constructObject(keyNode);
                    final String propName = key;

                    boolean found = Arrays.stream(Introspector.getBeanInfo(substitutionType).getPropertyDescriptors())
                            .anyMatch(pd -> pd.getName().equals(propName));
                    if (!found) { // search in aliases
                        found = getPropertyUtils().getProperties(substitutionType).stream()
                                .map(p -> p.getAnnotation(de.beosign.snakeyamlanno.annotation.Property.class))
                                .filter(anno -> anno != null)
                                .anyMatch(anno -> propName.equals(anno.key()));

                    }
                    if (!found) {
                        throw new YAMLException("Cannot find a property named " + propName + " in type " + substitutionType.getTypeName());
                    }

                } catch (YAMLException | IntrospectionException e) {
                    log.debug("Evaluating subsitution of type {}: Could not construct property {}.{}: {}", type, substitutionType.getName(), key,
                            e.getMessage());
                    isValidType = false;
                    break;
                }
            }
            if (isValidType) {
                validSubstitutionTypes.add(substitutionType);
            }

        }

        log.trace("Type = {}, found valid substitution types: {}", type, validSubstitutionTypes);
        return validSubstitutionTypes;
    }

    /**
     * This constructor implements the features "automatic type detection", "ignore error", "constructBy at property-level" and "singleton list parsing"
     * feature.
     * 
     * @author florian
     */
    protected class AnnotationAwareMappingConstructor extends ConstructMapping {
        @Override
        public Object construct(Node node) {
            if (Collection.class.isAssignableFrom(node.getType())) {
                return constructNodeAsList(node, super::construct);
            } else {
                return constructObject(node, super::construct);
            }
        }

        @Override
        protected Object constructJavaBean2ndStep(MappingNode node, Object object) {
            List<NodeTuple> nodeTuplesToBeRemoved = new ArrayList<>();

            Class<? extends Object> beanType = node.getType();
            List<NodeTuple> nodeValue = node.getValue();
            for (NodeTuple tuple : nodeValue) {
                ScalarNode keyNode = getKeyNode(tuple);

                keyNode.setType(String.class);
                String key = (String) AnnotationAwareConstructor.this.constructObject(keyNode);

                TypeDescription memberDescription = typeDefinitions.get(beanType);
                Property property = memberDescription == null ? getProperty(beanType, key) : memberDescription.getProperty(key);
                Node valueNode = tuple.getValueNode();

                nodeToPropertyMap.put(valueNode, property);

                if (property.getAnnotation(ConstructBy.class) != null) {
                    Object value = null;
                    try {
                        @SuppressWarnings("unchecked")
                        CustomConstructor<Object> cc = (CustomConstructor<Object>) property.getAnnotation(ConstructBy.class).value().newInstance();
                        Construct constructor = getConstructor(valueNode);
                        value = cc.construct(valueNode, constructor::construct);
                        property.set(object, value);
                        nodeTuplesToBeRemoved.add(tuple);
                    } catch (YAMLException e) {
                        throw e;
                    } catch (InstantiationException | IllegalAccessException e) {
                        throw new YAMLException(
                                "Custom constructor " + property.getAnnotation(ConstructBy.class).value() + //
                                        " on property " + object.getClass().getTypeName() + "::" + property + " cannot be created",
                                e);
                    } catch (Exception e) {
                        throw new YAMLException(
                                "Cannot set value of type " + (value != null ? value.getClass().getTypeName() : "null") + //
                                        " into property " + object.getClass().getTypeName() + "::" + property,
                                e);
                    }
                } else {
                    de.beosign.snakeyamlanno.annotation.Property propertyAnnotation = property.getAnnotation(de.beosign.snakeyamlanno.annotation.Property.class);
                    boolean ignoreExceptions = (propertyAnnotation != null && propertyAnnotation.ignoreExceptions());

                    if (ignoreExceptions) {
                        /* 
                         * Let YAML set the value.
                         */
                        try {
                            Construct constructor = getConstructor(valueNode);
                            constructor.construct(valueNode);
                        } catch (Exception e) {
                            log.debug("Ignore: Could not construct property {}.{}: {}", beanType, key, e.getMessage());
                            nodeTuplesToBeRemoved.add(tuple);
                        }
                    }
                }
            }

            // Remove nodes that are unconstructable or already created
            nodeTuplesToBeRemoved.forEach(nt -> node.getValue().remove(nt));
            return super.constructJavaBean2ndStep(node, object);
        }

    }

    /**
     * Enables creating a complex object from a scalar. Useful if object to be created cannot be modified so
     * adding a single argument constructor is not possible, e.g. enums.
     * Also checks if the node's type is a collection and in that case, converts it to a singleton list.
     * 
     * @author florian
     */
    protected class AnnotationAwareScalarConstructor extends ConstructScalar {
        @Override
        public Object construct(Node node) {
            if (Collection.class.isAssignableFrom(node.getType())) {
                return constructNodeAsList(node, super::construct);
            }
            return constructObject(node, super::construct);
        }
    }

    /**
     * @return all programmatically registered class-to-constructBy associations.
     */
    public Map<Class<?>, ConstructBy> getConstructByMap() {
        return constructByMap;
    }

    /**
     * Programmatically registers a {@link CustomConstructor} for a given type. This is a convenience method for putting something into
     * {@link #getConstructByMap()}.
     * 
     * @param forType type for which a CustomConverter is to be registered
     * @param customConstructorClass {@link CustomConstructor} type
     * @param <T> type for which a {@link CustomConstructor} is registered
     */
    public <T> void registerCustomConstructor(Class<T> forType, Class<? extends CustomConstructor<? extends T>> customConstructorClass) {
        constructByMap.put(forType, ConstructBy.Factory.of(customConstructorClass));
    }

    /**
     * Programmatically registers an {@link Instantiator} for a given type.
     * 
     * @param forType type for which an {@link Instantiator} is to be registered
     * @param instantiator {@link Instantiator} type
     */
    public void registerInstantiator(Class<?> forType, Class<? extends Instantiator> instantiator) {
        instantiateByMap.put(forType, InstantiateBy.Factory.of(instantiator));
    }

    /**
     * Programmatically registers an array of <i>substitution types</i> for a given type.
     * 
     * @param forType type for which an {@link Instantiator} is to be registered
     * @param substitutionTypes substitution types
     */
    public void registerSubstitutionTypes(Class<?> forType, Class<?>... substitutionTypes) {
        registerType(forType, Type.Factory.of(null, substitutionTypes));
    }

    /**
     * Programmatically registers a {@link Type} for a given type.
     * 
     * @param forType type for which an {@link Instantiator} is to be registered
     * @param type type; you can get an instance by the factory methods in {@link Type.Factory}
     */
    public void registerType(Class<?> forType, Type type) {
        typesMap.put(forType, type);
    }

    /**
     * Removes all manually added {@link Type} registrations.<br>
     * {@link Type} annotations on classes are uneffected by calling this method. In order to modify the behaviour induced by annotations, override
     * {@link #getTypeForClass(Class)}.
     */
    public void unregisterTypes() {
        typesMap.clear();
    }

    /**
     * Constructs an object by using either the default constructor (usually the SnakeYaml way) or - if registered - the custom constructor if there is one
     * defined for the given node type.
     * 
     * @param node node
     * @param defaultConstructor default constructor
     * @param <T> object type
     * @return constructed object
     */
    private <T> T constructObject(Node node, Function<Node, T> defaultConstructor) {
        ConstructBy constructBy = getConstructBy(node.getType());
        if (constructBy != null) {
            try {
                @SuppressWarnings("unchecked")
                CustomConstructor<T> constructor = (CustomConstructor<T>) constructBy.value().newInstance();
                return constructor.construct(node, defaultConstructor);
            } catch (InstantiationException | IllegalAccessException e) {
                throw new YAMLException("Cannot create custom constructor " + constructBy.value().getName(), e);
            }
        }
        return defaultConstructor.apply(node);
    }

    /**
     * Returns a matching {@link ConstructBy} annotation by using the following rules, given the node is of type <code>T</code>:<br>
     * Walk the superclass hierarchy of <code>T</code>, then all interfaces of <code>T</code>.<br>
     * For each superclass/interface <code>S super T</code>, check first if there is an entry in the {@link #getConstructByMap()} for <code>S</code>
     * and if so, return the ConstructBy from the map;
     * if not, check if <code>S</code> is annotated with ConstructBy, and if so, return the ConstructBy from the annotation.<br>
     * If there is no match for <code>S</code>, proceed with the next superclass/interface.
     * if no match was found after walking the whole hierarchy, <code>null</code> is returned.
     * 
     * @param type type
     * @return {@link ConstructBy} or <code>null</code> if no matching ConstructBy found
     */
    protected ConstructBy getConstructBy(Class<?> type) {
        ConstructBy constructByFoundInMap = null;
        ConstructBy constructByAnnotation = null;

        List<Class<?>> typesInHierarchy = new ArrayList<>();
        typesInHierarchy.add(type);
        typesInHierarchy.addAll(ClassUtils.getAllSuperclasses(type));
        typesInHierarchy.addAll(ClassUtils.getAllInterfaces(type));

        for (Class<?> typeToFindInMap : typesInHierarchy) {
            constructByFoundInMap = constructByMap.get(typeToFindInMap);
            if (constructByFoundInMap != null) {
                return constructByFoundInMap;
            }
            constructByAnnotation = typeToFindInMap.getDeclaredAnnotation(ConstructBy.class);
            if (constructByAnnotation != null) {
                return constructByAnnotation;
            }
        }

        return null;
    }

    /**
     * Returns a matching {@link InstantiateBy} annotation by using the following rule:
     * <ol>
     * <li>If there is an entry in the {@link #instantiateByMap} for <code>type</code> return the {@link InstantiateBy} from the map</li>
     * <li>If <code>type</code> is annotated with {@link InstantiateBy}, return the annotation.</li>
     * </ol>
     * If no match was found, <code>null</code> is returned.
     * 
     * @param type type
     * @return {@link InstantiateBy} or <code>null</code> if no matching {@link InstantiateBy} found
     */
    protected InstantiateBy getInstantiateBy(Class<?> type) {
        return instantiateByMap.getOrDefault(type, type.getAnnotation(InstantiateBy.class));
    }

    private static ScalarNode getKeyNode(NodeTuple tuple) {
        if (tuple.getKeyNode() instanceof ScalarNode) {
            return (ScalarNode) tuple.getKeyNode();
        } else {
            throw new YAMLException("Keys must be scalars but found: " + tuple.getKeyNode());
        }
    }

}
