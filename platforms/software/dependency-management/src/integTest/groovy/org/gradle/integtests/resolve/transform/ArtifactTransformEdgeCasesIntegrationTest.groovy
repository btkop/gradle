/*
 * Copyright 2024 the original author or authors.
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

package org.gradle.integtests.resolve.transform

import org.gradle.integtests.fixtures.AbstractIntegrationSpec

class ArtifactTransformEdgeCasesIntegrationTest extends AbstractIntegrationSpec implements ArtifactTransformTestFixture {
    def "demonstrate multiple distinct transformation chains"() {
        file("my-initial-file.txt") << "Contents"

        buildKotlinFile <<  """
            val color = Attribute.of("color", String::class.java)
            val shape = Attribute.of("shape", String::class.java)
            val matter = Attribute.of("matter", String::class.java)
            val texture = Attribute.of("texture", String::class.java)

            configurations {
                // Supply a square-blue-liquid variant
                consumable("squareBlueLiquidElements") {
                    attributes.attribute(shape, "square")
                    attributes.attribute(color, "blue")
                    attributes.attribute(matter, "liquid")
                    attributes.attribute(texture, "unknown")

                    outgoing {
                        artifact(file("my-initial-file.txt"))
                    }
                }

                dependencyScope("myDependencies")

                // Initial ask is for liquid, satisfied by the square-blue-liquid variant
                resolvable("resolveMe") {
                    extendsFrom(configurations.getByName("myDependencies"))
                    attributes.attribute(matter, "liquid")
                }
            }

            abstract class BrokenTransform : TransformAction<TransformParameters.None> {
                override fun transform(outputs: TransformOutputs) {
                    throw AssertionError("Should not actually be selected to run")
                }
            }

            dependencies {
                add("myDependencies", project(":"))

                // blue -> purple -> red
                registerTransform(BrokenTransform::class.java) {
                    from.attribute(color, "blue")
                    from.attribute(texture, "unknown")
                    to.attribute(color, "purple")
                    to.attribute(texture, "rough")
                }
                registerTransform(BrokenTransform::class.java) {
                    from.attribute(color, "purple")
                    to.attribute(color, "red")
                }

                // square -> triangle -> round
                registerTransform(BrokenTransform::class.java) {
                    from.attribute(shape, "square")
                    to.attribute(shape, "triangle")
                }
                registerTransform(BrokenTransform::class.java) {
                    from.attribute(shape, "triangle")
                    to.attribute(shape, "round")
                }

                // blue -> yellow -> red
                registerTransform(BrokenTransform::class.java) {
                    from.attribute(color, "blue")
                    from.attribute(texture, "unknown")
                    to.attribute(color, "yellow")
                    to.attribute(texture, "smooth")
                }
                registerTransform(BrokenTransform::class.java) {
                    from.attribute(color, "yellow")
                    to.attribute(color, "red")
                }

                // square -> flat -> round
                registerTransform(BrokenTransform::class.java) {
                    from.attribute(shape, "square")
                    to.attribute(shape, "flat")
                }
                registerTransform(BrokenTransform::class.java) {
                    from.attribute(shape, "flat")
                    to.attribute(shape, "round")
                }
            }

            val forceResolution by tasks.registering {
                inputs.files(configurations.getByName("resolveMe").incoming.artifactView {
                    // After getting initial square-blue-liquid variant with liquid request, we request something red-round
                    // There should be 2 separate transformation chains of equal length that produce this
                    attributes.attribute(color, "red")
                    attributes.attribute(shape, "round")
                }.artifacts.artifactFiles)

                doLast {
                    inputs.files.files.forEach { println(it) }
                }
            }
        """

        expect:
        fails "forceResolution"

        failure.assertHasDescription("Could not determine the dependencies of task ':forceResolution'.")
        failure.assertHasCause("Could not resolve all dependencies for configuration ':resolveMe'.")
        failure.assertHasErrorOutput("""   > Found multiple transforms that can produce a variant of root project : with requested attributes:""")
    }
}
