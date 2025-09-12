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

package org.gradle.nativeplatform.toolchain.internal;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Rename a method for Groovy specifically.
 * A duplicate of the annotated method will be created with the specified name.
 * This is ONLY visible to Groovy code.
 */
@Retention(RetentionPolicy.RUNTIME) // Retain at runtime so Groovy can access it
@Target(ElementType.METHOD)          // Apply to methods only
public @interface GroovyName {
    String value(); // New name for Groovy
}
