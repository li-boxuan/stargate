/*
 * Copyright The Stargate Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package io.stargate.sgv2.docsapi.api.v2.namespaces.collections.model.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Request body when creating a new collection.
 *
 * @param name Name of the collection.
 */
public record CreateCollectionDto(
    @Schema(description = "The name of the collection.", pattern = "\\w+", example = "cycling")
        @NotNull(message = "`name` is required to create a collection")
        @NotBlank(message = "`name` is required to create a collection")
        String name) {}
