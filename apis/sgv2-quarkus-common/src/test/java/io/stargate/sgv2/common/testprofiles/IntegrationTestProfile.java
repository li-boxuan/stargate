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
package io.stargate.sgv2.common.testprofiles;

import io.quarkus.test.junit.QuarkusTestProfile;
import io.stargate.sgv2.common.testresource.StargateTestResource;
import java.util.Collections;
import java.util.List;

/** Test profile for integration tests. Includes the {@link StargateTestResource}. */
public class IntegrationTestProfile implements QuarkusTestProfile {

  /** Adds StargateTestResource to the test resources. */
  @Override
  public List<TestResourceEntry> testResources() {
    TestResourceEntry stargateResource = new TestResourceEntry(StargateTestResource.class);
    return Collections.singletonList(stargateResource);
  }
}
