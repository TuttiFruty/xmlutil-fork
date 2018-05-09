/*
 * Copyright (c) 2018.
 *
 * This file is part of ProcessManager.
 *
 * ProcessManager is free software: you can redistribute it and/or modify it under the terms of version 3 of the
 * GNU Lesser General Public License as published by the Free Software Foundation.
 *
 * ProcessManager is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even
 * the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License along with ProcessManager.  If not,
 * see <http://www.gnu.org/licenses/>.
 */

package nl.adaptivity.xml.serialization

import kotlinx.serialization.*
import nl.adaptivity.util.xml.CompactFragment
import nl.adaptivity.xml.*
import kotlin.reflect.KClass

class XmlNameMap {
    private val classMap = mutableMapOf<QName, KClass<*>>()
    private val nameMap = mutableMapOf<KClass<*>, QName>()

    fun lookupClass(name: QName) = classMap[name.copy(prefix = "")]
    fun lookupName(kClass: KClass<*>) = nameMap[kClass]

    fun registerClass(kClass: KClass<*>) {
        val name = kClass.getSerialName() ?: QName(kClass.simpleName!!)
        registerClass(name, kClass)
    }

    fun registerClass(name: QName, kClass: KClass<*>) {
        classMap[name.copy(prefix = "")] = kClass
        nameMap[kClass] = name
    }
}

class XML(val context: SerialContext? = defaultSerialContext(),
          val repairNamespaces: Boolean = true,
          val omitXmlDecl: Boolean = true) {

    val nameMap = XmlNameMap()

    fun registerClass(kClass: KClass<*>) = nameMap.registerClass(kClass)

    fun registerClass(name: QName, kClass: KClass<*>) = nameMap.registerClass(name, kClass)

    inline fun <reified T : Any> stringify(obj: T): String = stringify(T::class, obj, context.klassSerializer(T::class))

    fun <T : Any> stringify(kClass: KClass<T>,
                            obj: T,
                            saver: KSerialSaver<T> = context.klassSerializer(kClass)): String {
        return buildString {
            val writer = XmlStreaming.newWriter(this, repairNamespaces, omitXmlDecl)
            try {
                toXml(kClass, obj, writer, saver)
            } finally {
                writer.close()
            }
        }
    }

    inline fun <reified T : Any> toXml(obj: T, target: XmlWriter) {
        toXml(T::class, obj, target, context.klassSerializer(T::class))
    }

    fun <T : Any> toXml(kClass: KClass<T>,
                        obj: T,
                        target: XmlWriter,
                        serializer: KSerialSaver<T> = context.klassSerializer(kClass)) {

        val output = XmlOutputImpl(context, target, kClass).Initial(kClass.getSerialName(), kClass.getChildName())

        output.write(serializer, obj)
    }

    inline fun <reified T : Any> parse(reader: XmlReader) = parse(T::class, reader)

    fun <T : Any> parse(kClass: KClass<T>,
                        reader: XmlReader,
                        loader: KSerialLoader<T> = context.klassSerializer(kClass)): T {
        val input = XmlInput(context, nameMap, reader, kClass.getSerialName(), kClass.getChildName(), 0, true)
        return input.read(loader)
    }

    fun <T : Any> parse(kClass: KClass<T>, str: String, loader: KSerialLoader<T> = context.klassSerializer(kClass)): T {
        return parse(kClass, XmlStreaming.newReader(str), loader)
/*


        val parser = Parser(str)
        val input = JsonInput(Mode.OBJ, parser)
        val result = input.read(loader)
        check(parser.tc == TC_EOF) { "Shall parse complete string"}
        return result
*/
    }

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun <T : Any> stringify(obj: T, kClass: KClass<T> = obj::class as KClass<T>): String =
            XML().run { stringify(kClass, obj, context.klassSerializer(kClass)) }

        inline fun <reified T : Any> stringify(obj: T): String = stringify(obj, T::class)

        fun <T : Any> parse(kClass: KClass<T>, str: String): T = XML().parse(kClass, str)
        fun <T : Any> parse(kClass: KClass<T>, str: String, loader: KSerialLoader<T>): T = XML().parse(kClass, str,
                                                                                                       loader)

        inline fun <reified T : Any> parse(str: String): T = parse(T::class, str)
        inline fun <reified T : Any> parse(str: String, loader: KSerialLoader<T>): T = parse(T::class, str, loader)

        fun <T : Any> parse(kClass: KClass<T>, reader: XmlReader): T = XML().parse(kClass, reader)
        fun <T : Any> parse(kClass: KClass<T>, reader: XmlReader, loader: KSerialLoader<T>): T = XML().parse(kClass,
                                                                                                             reader,
                                                                                                             loader)

        inline fun <reified T : Any> parse(reader: XmlReader): T = parse(T::class, reader)
        inline fun <reified T : Any> parse(reader: XmlReader, loader: KSerialLoader<T>): T = parse(T::class, reader,
                                                                                                   loader)
    }

    internal interface XmlCommon<QN : QName?> {
        var serialName: QN
        val childName: QName?
        val myCurrentTag: OutputDescriptor
        val namespaceContext: NamespaceContext

        fun getTagName(desc: KSerialClassDesc) =
            myCurrentTag.name//.also { serialName = it }


        fun KSerialClassDesc.getTagName(index: Int): QName {
            getAnnotationsForIndex(index).getXmlSerialName(serialName)?.let { return it }

            val name = getElementName(index)
            val i = name.indexOf(':')
            return when {
                i > 0 -> {
                    val prefix = name.substring(i + 1)
                    val ns = namespaceContext.getNamespaceURI(prefix) ?: throw IllegalArgumentException(
                        "Missing namespace for prefix $prefix")
                    QName(ns, name.substring(0, i), prefix)
                }
                else  -> QName(serialName?.namespaceURI ?: "", name, serialName?.prefix ?: "")
            }
        }

        fun KSerialClassDesc.getValueChild(): Int {
            if (associatedFieldsCount == 1) {
                return 0
            } else {
                // TODO actually determine this properly
                return KInput.UNKNOWN_NAME
            }
        }

        fun doGetTag(classDesc: KSerialClassDesc, index: Int): OutputDescriptor {
            return OutputDescriptor(classDesc, index, classDesc.outputKind(index), classDesc.getTagName(index))
        }
    }

    interface XmlOutput {
        val context: SerialContext?
        val target: XmlWriter
    }

    open class XmlOutputImpl internal constructor(context: SerialContext?,
                                                  override val target: XmlWriter,
                                                  final override var serialName: QName?,
                                                  override var childName: QName?) : TaggedOutput<OutputDescriptor>(), XmlCommon<QName?>, XmlOutput {

        init {
            this.context = context
        }

        override val myCurrentTag: OutputDescriptor get() = currentTag
        override val namespaceContext: NamespaceContext get() = target.namespaceContext

        internal constructor(context: SerialContext?, target: XmlWriter, targetType: KClass<*>?) :
            this(context, target, targetType?.getSerialName(), targetType?.getChildName())

        open fun copy(context: SerialContext? = this.context,
                      target: XmlWriter = this.target,
                      serialName: QName? = this.serialName,
                      childName: QName? = null) = XmlOutputImpl(context, target, serialName, childName)

        final override fun KSerialClassDesc.getTag(index: Int): OutputDescriptor {
            // just delegate to a function that can be overridden properly with super calls
            return doGetTag(this, index)
        }

        inner open class Base(override var serialName: QName,
                              override var childName: QName?) : TaggedOutput<OutputDescriptor>(), XmlCommon<QName>, XmlOutput {

            override val myCurrentTag: OutputDescriptor get() = currentTag
            override val namespaceContext: NamespaceContext get() = target.namespaceContext
            override val target: XmlWriter get() = this@XmlOutputImpl.target

            open fun OutputKind.effectiveKind() = when (this) {
                OutputKind.Unknown -> OutputKind.Attribute
                else               -> this
            }

            override fun writeBegin(desc: KSerialClassDesc, vararg typeParams: KSerializer<*>): KOutput {
                val tag = currentTag
                return writeBegin(desc, tag.name, tag.childName)
            }

            /**
             * Called when finished writing the current complex element.
             */
            override fun writeFinished(desc: KSerialClassDesc) {
                target.endTag(serialName)
            }

            override fun doGetTag(classDesc: KSerialClassDesc, index: Int): OutputDescriptor {
                return OutputDescriptor(classDesc, index, classDesc.outputKind(index), classDesc.getTagName(index))
            }

            final override fun KSerialClassDesc.getTag(index: Int): OutputDescriptor {
                // just delegate to a function that can be overridden properly with super calls
                return doGetTag(this, index)
            }

            override fun writeTaggedNull(tag: OutputDescriptor) {
                // Do nothing - in xml absense is null
            }

            override fun writeTaggedValue(tag: OutputDescriptor, value: Any) {
                if (value is XmlSerializable) {
                    value.serialize(target)
                } else {
                    super.writeTaggedValue(tag, value)
                }
            }

            override fun writeTaggedBoolean(tag: OutputDescriptor, value: Boolean) = writeTaggedString(tag,
                                                                                                       value.toString())

            override fun writeTaggedByte(tag: OutputDescriptor, value: Byte) = writeTaggedString(tag, value.toString())

            override fun writeTaggedChar(tag: OutputDescriptor, value: Char) = writeTaggedString(tag, value.toString())

            override fun writeTaggedDouble(tag: OutputDescriptor, value: Double) = writeTaggedString(tag,
                                                                                                     value.toString())

            override fun writeTaggedFloat(tag: OutputDescriptor, value: Float) = writeTaggedString(tag,
                                                                                                   value.toString())

            override fun writeTaggedInt(tag: OutputDescriptor, value: Int) = writeTaggedString(tag, value.toString())

            override fun writeTaggedLong(tag: OutputDescriptor, value: Long) = writeTaggedString(tag, value.toString())

            override fun writeTaggedShort(tag: OutputDescriptor, value: Short) = writeTaggedString(tag,
                                                                                                   value.toString())

            override fun writeTaggedString(tag: OutputDescriptor, value: String) {
                when (tag.kind.effectiveKind()) {
                    OutputKind.Unknown   -> throw UnsupportedOperationException("Unknown should never happen")
                    OutputKind.Attribute -> target.doWriteAttribute(tag.name, value)
                    OutputKind.Text      -> target.doText(value)
                    OutputKind.Element   -> doSmartStartTag(tag.name) {
                        text(value)
                    }
                }
            }

            open fun Any.doWriteAttribute(name: QName, value: String) {
                target.writeAttribute(name, value)
            }

            inline fun doSmartStartTag(name: QName, body: XmlWriter.() -> Unit) = target.run {
                doSmartStartTag(name)
                body()
                endTag(name)
            }

            open fun doSmartStartTag(name: QName) = target.smartStartTag(name)

            open fun Any.doText(value: String) = target.text(value)

        }

        inner class Initial(private val serialName: QName?, val childName: QName?) : ElementValueOutput(), XmlOutput {
            init {
                this.context = this@XmlOutputImpl.context
            }

            override val target: XmlWriter get() = this@XmlOutputImpl.target

            /**
             * Create an output for the element. We only need this as xml serialization must always start with an
             * element. We should not have any tag defined yet
             *
             * @param desc The description for the *new* element
             */
            override fun writeBegin(desc: KSerialClassDesc, vararg typeParams: KSerializer<*>): KOutput {
                val tagName = serialName ?: QName(desc.name.substringAfterLast('.'))
                return writeBegin(desc, tagName, childName)
            }
        }

        /**
         * Called when staring to write the children of this element.
         * @param desc The descriptor of the current element
         * @param typeParams The serializers for the elements
         */
        override fun writeBegin(desc: KSerialClassDesc, vararg typeParams: KSerializer<*>): KOutput {
            return writeBegin(desc, getTagName(desc), this.childName)
        }

        internal fun writeBegin(desc: KSerialClassDesc,
                                tagName: QName,
                                childName: QName?): KOutput {
            return when (desc.kind) {
                KSerialClassKind.LIST,
                KSerialClassKind.MAP,
                KSerialClassKind.SET         -> {
                    if (childName != null) {
                        doSmartStartTag(tagName)

                        // If the child tag has a different namespace uri that requires a namespace declaration
                        // And we didn't just declare the prefix here already then we will declare it here rather
                        // than on each child
                        if (tagName.prefix != childName.prefix && target.getNamespaceUri(
                                childName.prefix) != childName.namespaceURI) {
                            target.namespaceAttr(childName.prefix, childName.namespaceURI)
                        }
                    }
                    RepeatedWriter(context, target, tagName, childName)
                }
                KSerialClassKind.POLYMORPHIC -> {
                    val transparent = desc.associatedFieldsCount == 1 || currentTagOrNull?.currentAnnotations?.firstOrNull<XmlPolyChildren>() != null
                    if (!transparent) doSmartStartTag(tagName)

                    PolymorphicWriter(tagName, transparent)
                }
                KSerialClassKind.CLASS,
                KSerialClassKind.OBJECT,
                KSerialClassKind.SEALED      -> {
                    doSmartStartTag(tagName)
                    val lastInvertedIndex = desc.lastInvertedIndex()
                    if (lastInvertedIndex > 0) {
                        InvertedWriter(tagName, null, lastInvertedIndex)
                    } else {
                        Base(tagName, childName)
                    }
                }

                KSerialClassKind.ENTRY       -> TODO("Maps are not yet supported")//MapEntryWriter(currentTagOrNull)
                else                         -> throw SerializationException(
                    "Primitives are not supported at top-level")
            }
        }

        /**
         * Called when finished writing the current complex element.
         */
        override fun writeFinished(desc: KSerialClassDesc) {
            target.endTag(serialName!!)
        }

/*

        override fun writeElement(desc: KSerialClassDesc, index: Int): Boolean {
            if (desc.outputKind(index)) {
                target.smartStartTag(desc.getTagName(index).also { currentInnerTag = it })
            } else {
                pendingAttrName = desc.getTagName(index)
            }
            return true
        }
*/

        override fun <T> writeSerializableValue(saver: KSerialSaver<T>, value: T) {
            val tag = currentTag
            if (tag.name != serialName && tag.childName != childName) {
                copy(serialName = tag.name, childName = tag.childName).apply {
                    if (writeElement(tag.desc, tag.index))
                        writeSerializableValue(saver, value)
                }
            } else {
                super.writeSerializableValue(saver, value)
            }
        }

        override fun writeTaggedNull(tag: OutputDescriptor) {
            // Do nothing - in xml absense is null
        }

        override fun writeTaggedValue(tag: OutputDescriptor, value: Any) {
            if (value is XmlSerializable) {
                value.serialize(target)
            } else {
                super.writeTaggedValue(tag, value)
            }
        }

        override fun writeTaggedBoolean(tag: OutputDescriptor, value: Boolean) = writeTaggedString(tag,
                                                                                                   value.toString())

        override fun writeTaggedByte(tag: OutputDescriptor, value: Byte) = writeTaggedString(tag, value.toString())

        override fun writeTaggedChar(tag: OutputDescriptor, value: Char) = writeTaggedString(tag, value.toString())

        override fun writeTaggedDouble(tag: OutputDescriptor, value: Double) = writeTaggedString(tag, value.toString())

        override fun writeTaggedFloat(tag: OutputDescriptor, value: Float) = writeTaggedString(tag, value.toString())

        override fun writeTaggedInt(tag: OutputDescriptor, value: Int) = writeTaggedString(tag, value.toString())

        override fun writeTaggedLong(tag: OutputDescriptor, value: Long) = writeTaggedString(tag, value.toString())

        override fun writeTaggedShort(tag: OutputDescriptor, value: Short) = writeTaggedString(tag, value.toString())

        override fun writeTaggedString(tag: OutputDescriptor, value: String) {
            when (tag.kind) {
                OutputKind.Unknown   -> {
                    tag.kind = OutputKind.Attribute; writeTaggedString(tag, value)
                }
                OutputKind.Attribute -> doWriteAttribute(tag.name, value)
                OutputKind.Text      -> doText(value)
                OutputKind.Element   -> doSmartStartTag(tag.name) {
                    text(value)
                }
            }
        }

        open fun doWriteAttribute(name: QName, value: String) {
            target.writeAttribute(name, value)
        }

        inline fun doSmartStartTag(name: QName, body: XmlWriter.() -> Unit) {
            doSmartStartTag(name)
            target.body()
            target.endTag(name)
        }

        open fun doText(value: String) = target.text(value)
        /**
         * Wrapper function that will allow queing events
         */
        open fun doSmartStartTag(name: QName) = target.smartStartTag(name)

        private inner class InvertedWriter(serialName: QName,
                                           childName: QName?,
                                           val lastInvertedIndex: Int) : Base(serialName, childName) {

            val parentWriter get() = super.target

            override var target: XmlWriter = XmlBufferedWriter()
                private set

            override fun Any.doWriteAttribute(name: QName, value: String) {
                // Write attributes directly in all cases
                parentWriter.writeAttribute(name, value)
            }

            override fun doGetTag(classDesc: KSerialClassDesc, index: Int): OutputDescriptor {
                if (index > lastInvertedIndex) {
                    // If we are done, just flip to using a regular target
                    (target as? XmlBufferedWriter)?.flushTo(parentWriter)
                    target = parentWriter
                }
                // Now get the actual tag
                return super.doGetTag(classDesc, index)
            }

            override fun writeFinished(desc: KSerialClassDesc) {
                // Flush if we somehow haven't flushed yet
                (target as? XmlBufferedWriter)?.flushTo(parentWriter)
                target = parentWriter

                super.writeFinished(desc)
            }
        }

        private inner class PolymorphicWriter(serialName: QName, val transparent: Boolean = false) :
            Base(serialName, null), XmlOutput {

            override fun doGetTag(classDesc: KSerialClassDesc, index: Int): OutputDescriptor {
                val tagName: QName
                val outputKind: OutputKind
                when (index) {
                    0 -> {
                        tagName = QName("type")
                        outputKind = OutputKind.Attribute
                    }
                    else -> {
                        tagName = if (transparent) serialName else classDesc.getTagName(index)
                        outputKind = OutputKind.Element
                    }
                }

                return OutputDescriptor(classDesc, index, outputKind, tagName, childName)
            }

            override fun shouldWriteElement(desc: KSerialClassDesc, tag: OutputDescriptor, index: Int): Boolean {
                return !(transparent && index==0)
            }

            override fun writeFinished(desc: KSerialClassDesc) {
                // Don't write anything if we're transparent
                if (!transparent) {
                    super.writeFinished(desc)
                }
            }
        }

        private class RepeatedWriter(context: SerialContext?,
                                     target: XmlWriter,
                                     serialName: QName,
                                     childName: QName?) : XmlOutputImpl(context, target, serialName, childName) {

            override fun copy(context: SerialContext?,
                              target: XmlWriter,
                              serialName: QName?,
                              childName: QName?): RepeatedWriter {
                return RepeatedWriter(context, target, serialName!!, childName)
            }

            override fun shouldWriteElement(desc: KSerialClassDesc, tag: OutputDescriptor, index: Int): Boolean {
                return index != 0
            }

            override fun doGetTag(classDesc: KSerialClassDesc, index: Int): OutputDescriptor {
                val name = childName ?: classDesc.getAnnotationsForIndex(index).getXmlSerialName(serialName)
                           ?: serialName
                           ?: QName(classDesc.name)

                val specifiedKind = classDesc.outputKind(
                    index).let { if (it != OutputKind.Text) OutputKind.Element else it }
                return OutputDescriptor(classDesc, index, specifiedKind, name)
            }

            override fun writeFinished(desc: KSerialClassDesc) {
                if (childName != null) {
                    super.writeFinished(desc)
                }
            }
        }

    }

    open class XmlInput internal constructor(context: SerialContext?,
                                             val nameMap: XmlNameMap,
                                             val input: XmlReader,
                                             override var serialName: QName?,
                                             override val childName: QName?,
                                             childCount: Int,
                                             private val isTagNotReadYet: Boolean = false,
                                             private var attrIndex: Int = -1) : TaggedInput<OutputDescriptor>(), XmlCommon<QName?> {
        init {
            this.context = context
        }

        fun copy(context: SerialContext? = this.context,
                 nameMap: XmlNameMap = this.nameMap,
                 input: XmlReader = this.input,
                 serialName: QName? = this.serialName,
                 childName: QName? = this.childName,
                 childCount: Int,
                 isTagNotReadYet: Boolean = false,
                 attrIndex: Int = -1) = XmlInput(context, nameMap, input, serialName, childName, childCount,
                                                 isTagNotReadYet,
                                                 attrIndex)

        final override fun KSerialClassDesc.getTag(index: Int): OutputDescriptor {
            markItemSeen(index)
            return doGetTag(this, index)
        }

        open fun markItemSeen(index: Int) {
            seenItems[index] = true
        }


        override val myCurrentTag: OutputDescriptor get() = currentTag

        override val namespaceContext: NamespaceContext get() = input.namespaceContext

        private var _nameMap: Map<QName, Int>? = null

        private val seenItems = BooleanArray(childCount)
        private var nulledItemsIdx = -1

        fun QName.normalize(): QName {
            return when {
                namespaceURI.isEmpty() -> copy(namespaceURI = namespaceContext.getNamespaceURI(prefix) ?: "",
                                               prefix = "")
                else                   -> copy(prefix = "")
            }
        }

        open fun KSerialClassDesc.indexOf(name: QName): Int {
            val map = _nameMap ?: mutableMapOf<QName, Int>().also { map ->
                for (i in 0 until associatedFieldsCount) {
                    map[getTagName(i).normalize()] = i
                }

                _nameMap = map
            }

            return map.get(name.normalize()) ?: throw SerializationException(
                "Could not find a field for name $name\n  candidates were: ${map.keys.joinToString()}")
        }

        internal class AnonymousListInput(context: SerialContext?,
                                          nameMap: XmlNameMap,
                                          input: XmlReader,
                                          childName: QName) : XmlInput(
            context, nameMap, input, childName, null, 2) {
            var finished = false

            override fun getTagName(desc: KSerialClassDesc): QName {
                return serialName!!
            }

            override fun markItemSeen(index: Int) {
                // do Nothing. List items are not nullable in XML
            }

            override fun readElement(desc: KSerialClassDesc): Int {
                return when {
                    finished -> KInput.READ_DONE
                    else     -> {
                        finished = true; 1
                    }
                }
            }
        }

        internal class NamedListInput(context: SerialContext?,
                                      nameMap: XmlNameMap,
                                      input: XmlReader,
                                      serialName: QName,
                                      childName: QName) : XmlInput(context, nameMap, input, serialName, childName, 2) {
            var childCount = 0

            override fun readElement(desc: KSerialClassDesc): Int {
                return when (input.nextTag()) {
                    EventType.END_ELEMENT -> KInput.READ_DONE
                    else                  -> ++childCount // This is important to ensure appending in the list.
                }
            }

            override fun doGetTag(classDesc: KSerialClassDesc, index: Int): OutputDescriptor {
                // The classDesc only has indices 0 and 1. Just make sure to return the needed tag, rather than the "truth"
                return OutputDescriptor(classDesc, 1, OutputKind.Element, childName!!)
            }

            override fun markItemSeen(index: Int) {
                // do Nothing. List items are not nullable in XML
            }
        }

        internal class PolymorphicInput(context: SerialContext?,
                                        nameMap: XmlNameMap,
                                        input: XmlReader,
                                        val transparent: Boolean = false) :
            XmlInput(context, nameMap, input, null, null, 2, attrIndex = 0) {
            override fun readElement(desc: KSerialClassDesc): Int {
                if (transparent) return 1
                return KInput.READ_ALL
            }

            override fun KSerialClassDesc.indexOf(name: QName): Int {
                return if (name.namespaceURI == "" && name.localPart == "type") 0 else 1
            }

            override fun <T> readSerializableValue(loader: KSerialLoader<T>): T {
                if (!transparent) input.nextTag()
                return super.readSerializableValue(loader)
            }

        }

        override fun readBegin(desc: KSerialClassDesc, vararg typeParams: KSerializer<*>): KInput {
            val tagName = if (currentTagOrNull == null) (serialName ?: QName(
                desc.name.substringAfterLast('.'))) else getTagName(desc)
            if (isTagNotReadYet) input.nextTag()

            input.require(EventType.START_ELEMENT, tagName.namespaceURI, tagName.localPart)

            return when (desc.kind) {
                KSerialClassKind.LIST,
                KSerialClassKind.MAP,
                KSerialClassKind.SET         -> {
                    val t = currentTag
                    t.kind = OutputKind.Element

                    val childName = t.desc.getAnnotationsForIndex(t.index).getChildName()
                    return when (childName) {
                        null -> AnonymousListInput(context, nameMap, input, tagName)
                        else -> NamedListInput(context, nameMap, input, tagName, childName)
                    }
                }
                KSerialClassKind.POLYMORPHIC -> {
                    PolymorphicInput(context, nameMap, input)
                }
                KSerialClassKind.CLASS,
                KSerialClassKind.OBJECT,
                KSerialClassKind.SEALED      -> copy(serialName = tagName, attrIndex = 0,
                                                     childCount = desc.associatedFieldsCount)

                KSerialClassKind.ENTRY       -> TODO("Maps are not yet supported")//MapEntryWriter(currentTagOrNull)
                else                         -> throw SerializationException(
                    "Primitives are not supported at top-level")
            }

        }

        fun nextNulledItemsIdx(desc: KSerialClassDesc) {
            for (i in (nulledItemsIdx + 1) until seenItems.size) {
                if (!(seenItems[i] || desc.isOptional(i))) {
                    nulledItemsIdx = i
                    return
                }
            }
            nulledItemsIdx = seenItems.size
        }

        override fun readElement(desc: KSerialClassDesc): Int {
/*
            when (desc.kind) {
                KSerialClassKind.MAP,
                KSerialClassKind.LIST,
                KSerialClassKind.SET -> {
                    return 1 // Just read a single element (always at index 1 - index 0 is the size)
                }
            }
*/
            if (nulledItemsIdx >= 0) {
                val sn = serialName!!
                input.require(EventType.END_ELEMENT, sn.namespaceURI, sn.localPart)

                if (nulledItemsIdx >= seenItems.size) return KInput.READ_DONE
                val i = nulledItemsIdx
                nextNulledItemsIdx(desc)
                return i
            }

            if (attrIndex >= 0 && attrIndex < input.attributeCount) {

                val name = input.getAttributeName(attrIndex)

                return if (name.getNamespaceURI() == XMLConstants.XMLNS_ATTRIBUTE_NS_URI ||
                           name.prefix == XMLConstants.XMLNS_ATTRIBUTE ||
                           (name.prefix.isEmpty() && name.localPart == XMLConstants.XMLNS_ATTRIBUTE)) {
                    // Ignore namespace decls
                    readElement(desc)
                } else {
                    desc.indexOf(name)
                }
            }
            attrIndex = -1 // Ensure to reset here

            // TODO validate correctness of type
            for (eventType in input) {
                if (!eventType.isIgnorable) {
                    return when (eventType) {
                        EventType.END_ELEMENT   -> {
                            nextNulledItemsIdx(desc)
                            when {
                                nulledItemsIdx < seenItems.size -> nulledItemsIdx
                                else                            -> KInput.READ_DONE
                            }
                        }
                        EventType.TEXT          -> desc.getValueChild()
                        EventType.ATTRIBUTE     -> desc.indexOf(input.name)
                        EventType.START_ELEMENT -> desc.indexOf(input.name)
                        else                    -> throw SerializationException("Unexpected event in stream")
                    }
                }
            }
            return KInput.READ_DONE
        }

        override fun <T> readSerializableValue(loader: KSerialLoader<T>): T {
            return super.readSerializableValue(loader)
        }

        override fun <T> updateSerializableValue(loader: KSerialLoader<T>, desc: KSerialClassDesc, old: T): T {
            return super.updateSerializableValue(loader, desc, old)
        }

        override fun readTaggedNotNullMark(tag: OutputDescriptor): Boolean {
            return nulledItemsIdx < 0 // If we are not yet reading "missing values" we have no nulls
        }

        override fun readTaggedString(tag: OutputDescriptor): String {
            if (nulledItemsIdx >= 0) throw MissingFieldException(tag.desc.getElementName(tag.index))
            return when (tag.kind) {
                OutputKind.Element   -> input.readSimpleElement()
                OutputKind.Text      -> input.allText()
                OutputKind.Attribute -> {
                    input.getAttributeValue(attrIndex).also { attrIndex++ }
                }
                OutputKind.Unknown   -> run { tag.kind = OutputKind.Attribute; readTaggedString(tag) }
            }
        }

        override fun readTaggedBoolean(tag: OutputDescriptor) = readTaggedString(tag).toBoolean()

        override fun readTaggedByte(tag: OutputDescriptor) = readTaggedString(tag).toByte()

        override fun readTaggedChar(tag: OutputDescriptor) = readTaggedString(tag).single()

        override fun readTaggedDouble(tag: OutputDescriptor) = readTaggedString(tag).toDouble()

        override fun readTaggedFloat(tag: OutputDescriptor) = readTaggedString(tag).toFloat()

        override fun readTaggedInt(tag: OutputDescriptor) = when (tag.desc.kind) {
            KSerialClassKind.SET,
            KSerialClassKind.LIST,
            KSerialClassKind.MAP -> if (tag.index == 0) 1 else readTaggedString(
                tag).toInt() // Always return elements one by one (there is no list size)

            else                 -> readTaggedString(tag).toInt()
        }

        override fun readTaggedLong(tag: OutputDescriptor) = readTaggedString(tag).toLong()

        override fun readTaggedShort(tag: OutputDescriptor) = readTaggedString(tag).toShort()
    }
}


private fun defaultSerialContext() = SerialContext().apply {
    registerSerializer(CompactFragment::class, CompactFragmentSerializer())
}

fun Collection<Annotation>.getXmlSerialName(current: QName?): QName? {
    val serialName = firstOrNull<XmlSerialName>()
    return when {
        serialName == null -> null
        serialName.namespace == UNSET_ANNOTATION_VALUE
                           -> if (current == null) {
            QName(serialName.value)
        } else {
            QName(current.namespaceURI, serialName.value, current.prefix)
        }

        serialName.prefix == UNSET_ANNOTATION_VALUE
                           -> QName(serialName.namespace, serialName.value)

        else               -> QName(serialName.namespace, serialName.value, serialName.prefix)
    }
}

fun Collection<Annotation>.getChildName(): QName? {
    val childrenName = firstOrNull<XmlChildrenName>()
    return when {
        childrenName == null -> null
        childrenName.namespace == UNSET_ANNOTATION_VALUE
                             -> QName(childrenName.value)

        childrenName.prefix == UNSET_ANNOTATION_VALUE
                             -> QName(childrenName.namespace, childrenName.value)

        else                 -> QName(childrenName.namespace, childrenName.value, childrenName.prefix)
    }
}

fun <T : Any> KClass<T>.getSerialName(): QName? {
    return annotations.getXmlSerialName(null)
}

fun <T : Any> KClass<T>.getChildName(): QName? {
    return annotations.getChildName()
}

/**
 * Specify more detailed name information than can be provided by [SerialName].
 */
@SerialInfo
@Target(AnnotationTarget.CLASS, AnnotationTarget.PROPERTY)
annotation class XmlSerialName(val value: String,
                               val namespace: String = UNSET_ANNOTATION_VALUE,
                               val prefix: String = UNSET_ANNOTATION_VALUE)

/**
 * Indicate the valid poly children for this element
 */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
annotation class XmlPolyChildren(val value: Array<String>)

/**
 * Specify additional information about child values. This is only used for primitives, not for classes that have their
 * own independent name
 */
@SerialInfo
@Target(AnnotationTarget.CLASS, AnnotationTarget.PROPERTY)
annotation class XmlChildrenName(val value: String,
                                 val namespace: String = UNSET_ANNOTATION_VALUE,
                                 val prefix: String = UNSET_ANNOTATION_VALUE)

/**
 * Force a property that could be an attribute to be an element
 */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
annotation class XmlElement(val value: Boolean = true)

/**
 * Force a property to be element content
 */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
annotation class XmlValue(val value: Boolean = true)

enum class OutputKind { Element, Attribute, Text, Unknown }
data class OutputDescriptor(val desc: KSerialClassDesc,
                            val index: Int,
                            var kind: OutputKind,
                            var name: QName,
                            val childName: QName? = desc.getAnnotationsForIndex(index).getChildName()) {
//    val childName: QName? by lazy { desc.getAnnotationsForIndex(index).getChildName() }
    val currentAnnotations get() = desc.getAnnotationsForIndex(index)
}

internal const val UNSET_ANNOTATION_VALUE = "ZXCVBNBVCXZ"

private inline fun <reified T> Iterable<*>.firstOrNull(): T? {
    for (e in this) {
        if (e is T) return e
    }
    return null
}


private fun KSerialClassDesc.outputKind(index: Int): OutputKind {
    // lists will always be elements
    getAnnotationsForIndex(index).firstOrNull<XmlChildrenName>()?.let { return OutputKind.Element }
    getAnnotationsForIndex(
        index).firstOrNull<XmlElement>()?.let { return if (it.value) OutputKind.Element else OutputKind.Attribute }
    return OutputKind.Unknown
}

private fun KSerialClassDesc.isOptional(index: Int): Boolean {
    return getAnnotationsForIndex(index).firstOrNull<Optional>() != null
}

/**
 * Determine the last index that may be an attribute (this is on incomplete information).
 * This will only return a positive value if there is an element before this attribute.
 */
private fun KSerialClassDesc.lastInvertedIndex(): Int {
    var seenElement = false
    var lastAttrIndex = -1
    for (i in 0 until associatedFieldsCount) {
        when (outputKind(i)) {
            OutputKind.Text,
            OutputKind.Element -> seenElement = true
            else               -> if (seenElement) lastAttrIndex = i
        }
    }
    return lastAttrIndex
}

fun QName.copy(namespaceURI: String = this.namespaceURI,
               localPart: String = this.localPart,
               prefix: String = this.prefix) = QName(namespaceURI, localPart, prefix)