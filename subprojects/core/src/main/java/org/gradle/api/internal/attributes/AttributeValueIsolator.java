/*
 * Copyright 2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.api.internal.attributes;

import org.gradle.api.internal.model.NamedObjectInstantiator;
import org.gradle.internal.Cast;
import org.gradle.internal.isolation.Isolatable;
import org.gradle.internal.isolation.IsolatableFactory;
import org.gradle.internal.service.scopes.Scope;
import org.gradle.internal.service.scopes.ServiceScope;
import org.gradle.internal.snapshot.impl.CoercingStringValueSnapshot;

@ServiceScope(Scope.BuildSession.class)
public class AttributeValueIsolator {

    private final IsolatableFactory isolatableFactory;
    private final NamedObjectInstantiator instantiator;

    public AttributeValueIsolator(IsolatableFactory isolatableFactory, NamedObjectInstantiator instantiator) {
        this.isolatableFactory = isolatableFactory;
        this.instantiator = instantiator;
    }

    public <T> Isolatable<T> isolate(T value) {
        if (value instanceof String) {
            return Cast.uncheckedNonnullCast(new CoercingStringValueSnapshot((String) value, instantiator));
        } else {
            return isolatableFactory.isolate(value);
        }
    }
}
