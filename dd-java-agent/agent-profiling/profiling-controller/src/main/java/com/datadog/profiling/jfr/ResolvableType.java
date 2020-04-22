package com.datadog.profiling.jfr;

import java.util.List;
import java.util.function.Consumer;

class ResolvableType implements Type {
  private final String typeName;
  private final Metadata metadata;

  private volatile Type delegate;

  ResolvableType(String typeName, Metadata metadata) {
    this.typeName = typeName;
    this.metadata = metadata;
  }

  private void checkResolved() {
    if (delegate == null) {
      throw new IllegalStateException();
    }
  }

  @Override
  public long getId() {
    checkResolved();
    return delegate.getId();
  }

  @Override
  public boolean hasConstantPool() {
    checkResolved();
    return delegate.hasConstantPool();
  }

  @Override
  public TypedValue asValue(String value) {
    checkResolved();
    return delegate.asValue(value);
  }

  @Override
  public TypedValue asValue(byte value) {
    checkResolved();
    return delegate.asValue(value);
  }

  @Override
  public TypedValue asValue(char value) {
    checkResolved();
    return delegate.asValue(value);
  }

  @Override
  public TypedValue asValue(short value) {
    checkResolved();
    return delegate.asValue(value);
  }

  @Override
  public TypedValue asValue(int value) {
    checkResolved();
    return delegate.asValue(value);
  }

  @Override
  public TypedValue asValue(long value) {
    checkResolved();
    return delegate.asValue(value);
  }

  @Override
  public TypedValue asValue(float value) {
    checkResolved();
    return delegate.asValue(value);
  }

  @Override
  public TypedValue asValue(double value) {
    checkResolved();
    return delegate.asValue(value);
  }

  @Override
  public TypedValue asValue(boolean value) {
    checkResolved();
    return delegate.asValue(value);
  }

  @Override
  public TypedValue asValue(Consumer<FieldValueBuilder> fieldAccess) {
    checkResolved();
    return delegate.asValue(fieldAccess);
  }

  @Override
  public TypedValue nullValue() {
    checkResolved();
    return delegate.nullValue();
  }

  @Override
  public ConstantPool getConstantPool() {
    checkResolved();
    return delegate.getConstantPool();
  }

  @Override
  public Types getTypes() {
    checkResolved();
    return delegate.getTypes();
  }

  @Override
  public boolean isBuiltin() {
    checkResolved();
    return delegate.isBuiltin();
  }

  @Override
  public boolean isSimple() {
    checkResolved();
    return delegate.isSimple();
  }

  @Override
  public String getSupertype() {
    checkResolved();
    return delegate.getSupertype();
  }

  @Override
  public List<TypedField> getFields() {
    checkResolved();
    return delegate.getFields();
  }

  @Override
  public boolean canAccept(Object value) {
    checkResolved();
    return delegate.canAccept(value);
  }

  @Override
  public String getTypeName() {
    checkResolved();
    return delegate.getTypeName();
  }

  @Override
  public boolean isSame(NamedType other) {
    checkResolved();
    return delegate.isSame(other);
  }

  boolean resolve() {
    Type resolved = metadata.getType(typeName, false);
    if (resolved instanceof BaseType) {
      delegate = resolved;
      return true;
    }
    return false;
  }
}
