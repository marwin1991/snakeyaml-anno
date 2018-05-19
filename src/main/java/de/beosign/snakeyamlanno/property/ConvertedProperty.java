package de.beosign.snakeyamlanno.property;

import java.util.Objects;

import org.yaml.snakeyaml.introspector.Property;

import de.beosign.snakeyamlanno.convert.Converter;

/**
 * This special property type is used to indicate that a value must be converted when being set into the model class.
 * 
 * @author florian
 */
public class ConvertedProperty extends AnnotatedProperty {
    private final Converter<Object> converter;

    /**
     * New instance.
     * 
     * @param originalProperty property that was discovered and is now used as a delegate for this property
     * @param converterClass converter class
     * @throws IllegalAccessException if converter class cannot be accessed
     * @throws InstantiationException if converter class cannot be instantiated
     */
    @SuppressWarnings("unchecked")
    public ConvertedProperty(Property originalProperty, Class<? extends Converter<?>> converterClass) throws InstantiationException, IllegalAccessException {
        // use Object.class because otherwise Snakeyaml tries to already construct the concrete type without using the converted value which will fail
        super(originalProperty.getName(), Object.class, originalProperty);

        this.converter = (Converter<Object>) converterClass.newInstance();
    }

    /**
     * Setter does nothing.
     */
    @Override
    public void set(Object object, Object value) throws Exception {
        super.set(object,  converter.convertToModel(Objects.toString(value)));
    }

    @Override
    public Object get(Object object) {
        return converter.convertToYaml(super.get(object));
    }

}
