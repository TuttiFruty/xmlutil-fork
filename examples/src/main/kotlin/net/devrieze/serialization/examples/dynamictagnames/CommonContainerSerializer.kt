/*
 * Copyright (c) 2020.
 *
 * This file is part of xmlutil.
 *
 * This file is licenced to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You should have received a copy of the license with the source distribution.
 * Alternatively, you may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package net.devrieze.serialization.examples.dynamictagnames

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeStructure
import kotlinx.serialization.serializer
import nl.adaptivity.xmlutil.*
import nl.adaptivity.xmlutil.serialization.InputKind
import nl.adaptivity.xmlutil.serialization.XML

/**
 * A common base class that contains the actual code needed to serialize/deserialize the container.
 */
abstract class CommonContainerSerializer : KSerializer<Container> {
    /** We need to have the serializer for the elements */
    private val elementSerializer = serializer<TestElement>()

    /** Autogenerated descriptors don't work correctly here. */
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("Container") {
        element("data", ListSerializer(elementSerializer).descriptor)
    }

    override fun deserialize(decoder: Decoder): Container {
        // XmlInput is designed as an interface to test for to allow custom serializers
        if (decoder is XML.XmlInput) { // We treat XML different, using a separate method for clarity
            return deserializeDynamic(decoder, decoder.input)
        } else { // Simple default decoder implementation that delegates parsing the data to the ListSerializer
            val data = decoder.decodeStructure(descriptor) {
                decodeSerializableElement(descriptor, 0, ListSerializer(elementSerializer))
            }
            return Container(data)
        }
    }

    /**
     * This function is the meat to deserializing the container with dynamic tag names. Note that
     * because we use xml there is no point in going through the (anonymous) list dance. Doing that
     * would be an additional complication.
     */
    fun deserializeDynamic(decoder: Decoder, reader: XmlReader): Container {
        val xml = delegateFormat(decoder) // get the format for deserializing

        // We need the descriptor for the element. xmlDescriptor returns a rootDescriptor, so the actual descriptor is
        // its (only) child.
        val elementXmlDescriptor = xml.xmlDescriptor(elementSerializer).getElementDescriptor(0)

        // A list to collect the data
        val dataList = mutableListOf<TestElement>()

        decoder.decodeStructure(descriptor) {
            // Finding the children is actually not left to the serialization framework, but
            // done by "hand"
            while (reader.next() != EventType.END_ELEMENT) {
                when (reader.eventType) {
                    EventType.COMMENT,
                    EventType.IGNORABLE_WHITESPACE -> {
                        // Comments and whitespace are just ignored
                    }
                    EventType.ENTITY_REF,
                    EventType.TEXT -> {
                        if (reader.text.isNotBlank()) {
                            // Some parsers can return whitespace as text instead of ignorable whitespace

                            // Use the handler from the configuration to throw the exception.
                            @OptIn(ExperimentalXmlUtilApi::class)
                            xml.config.policy.handleUnknownContentRecovering(
                                reader,
                                InputKind.Text,
                                elementXmlDescriptor,
                                null,
                                emptyList()
                            )
                        }
                    }
                    // It's best to still check the name before parsing
                    EventType.START_ELEMENT -> {
                        if (reader.namespaceURI.isEmpty() && reader.localName.startsWith("Test_")) {
                            // When reading the child tag we use the DynamicTagReader to present normalized XML to the
                            // deserializer for elements
                            val filter = DynamicTagReader(reader, elementXmlDescriptor)

                            // The test element can now be decoded as normal (with the filter applied)
                            val testElement = xml.decodeFromReader(elementSerializer, filter)
                            dataList.add(testElement)
                        } else { // handling unexpected tags
                            @OptIn(ExperimentalXmlUtilApi::class)
                            xml.config.policy.handleUnknownContentRecovering(
                                reader,
                                InputKind.Element,
                                elementXmlDescriptor,
                                reader.name,
                                listOf("Test_??")
                            )
                        }
                    }

                    else -> // other content that shouldn't happen
                        throw XmlException("Unexpected tag content")
                }
            }
        }
        return Container(dataList)
    }

    override fun serialize(encoder: Encoder, value: Container) {
        if (encoder is XML.XmlOutput) { // When we are using the xml format use the serializeDynamic method
            return serializeDynamic(encoder, encoder.target, value.data)
        } else { // Otherwise just manually do the encoding that would have been generated
            encoder.encodeStructure(descriptor) {
                encodeSerializableElement(descriptor, 0, ListSerializer(elementSerializer), value.data)
            }
        }
    }

    /**
     * This function provides the actual dynamic serialization
     */
    fun serializeDynamic(encoder: Encoder, target: XmlWriter, data: List<TestElement>) {
        val xml = delegateFormat(encoder) // get the format for deserializing

        // We need the descriptor for the element. xmlDescriptor returns a rootDescriptor, so the actual descriptor is
        // its (only) child.
        val elementXmlDescriptor = xml.xmlDescriptor(elementSerializer).getElementDescriptor(0)

        encoder.encodeStructure(descriptor) { // create the structure (will write the tags of Container)
            for (element in data) { // write each element
                // We need a writer that does the renaming from the normal format to the dynamic format
                // It is passed the string of the id to add.
                val writer = DynamicTagWriter(target, elementXmlDescriptor, element.id.toString())

                // Normal delegate writing of the element
                xml.encodeToWriter(writer, elementSerializer, element)
            }
        }
    }

    // These functions abstract away getting the delegate format in improved or compat way.
    abstract fun delegateFormat(decoder: Decoder): XML
    abstract fun delegateFormat(encoder: Encoder): XML
}