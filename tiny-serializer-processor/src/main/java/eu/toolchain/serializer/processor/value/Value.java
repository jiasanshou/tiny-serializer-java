package eu.toolchain.serializer.processor.value;

import java.util.Optional;

import javax.lang.model.element.Element;

import lombok.Data;

@Data
public class Value {
    private final ValueType type;
    private final String name;
    private final String accessor;
    private final String variableName;
    private final Optional<Integer> id;
    private final Optional<Integer> constructorOrder;
}