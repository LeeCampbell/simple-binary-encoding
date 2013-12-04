/*
 * Copyright 2013 Real Logic Ltd.
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
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.co.real_logic.sbe.generation.cSharp;

import uk.co.real_logic.sbe.PrimitiveType;
import uk.co.real_logic.sbe.PrimitiveValue;
import uk.co.real_logic.sbe.generation.CodeGenerator;
import uk.co.real_logic.sbe.generation.OutputManager;
import uk.co.real_logic.sbe.ir.Encoding;
import uk.co.real_logic.sbe.ir.IntermediateRepresentation;
import uk.co.real_logic.sbe.ir.Signal;
import uk.co.real_logic.sbe.ir.Token;
import uk.co.real_logic.sbe.util.Verify;

import java.io.IOException;
import java.io.Writer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import static uk.co.real_logic.sbe.generation.cSharp.CSharpUtil.*;

public class CSharpGenerator implements CodeGenerator
{
    /** Class name to be used for visitor pattern that accesses the message header. */
    public static final String MESSAGE_HEADER_TYPE = "MessageHeader";
    public static final String FIXED_FLYWEIGHT_TYPE = "IFixedFlyweight";
    public static final String MESSAGE_FLYWEIGHT_TYPE = "IMessageFlyweight";

    private static final String BASE_INDENT = "";
    private static final String INDENT = "    ";

    private final IntermediateRepresentation ir;
    private final OutputManager outputManager;

    public CSharpGenerator(final IntermediateRepresentation ir, final OutputManager outputManager)
            throws IOException
    {
        Verify.notNull(ir, "ir");
        Verify.notNull(outputManager, "outputManager");

        this.ir = ir;
        this.outputManager = outputManager;
    }

    public void generateMessageHeaderStub() throws IOException
    {
        try (final Writer out = outputManager.createOutput(MESSAGE_HEADER_TYPE))
        {
            out.append(generateFileHeader(ir.packageName()));  // TODO ODE review generated namespace
            out.append(generateClassDeclaration(MESSAGE_HEADER_TYPE, FIXED_FLYWEIGHT_TYPE));
            out.append(generateFixedFlyweightCode(ir.header().get(0).size()));

            final List<Token> tokens = ir.header();
            out.append(generatePrimitivePropertyEncodings(MESSAGE_HEADER_TYPE, tokens.subList(1, tokens.size() - 1), BASE_INDENT));

            out.append("}\n");
        }
    }

    public void generateTypeStubs() throws IOException
    {
        // TODO ODE: CPP returns list of types here, do we need that?

        for (final List<Token> tokens : ir.types())
        {
            switch (tokens.get(0).signal())
            {
                case BEGIN_ENUM:
                    generateEnum(tokens);
                    break;

                case BEGIN_SET:
                    generateBitSet(tokens);
                    break;

                case BEGIN_COMPOSITE:
                    generateComposite(tokens);
                    break;
            }
        }
    }

    public void generate() throws IOException
    {
        generateMessageHeaderStub();
        generateTypeStubs();

        for (final List<Token> tokens : ir.messages())
        {
            final Token msgToken = tokens.get(0);
            final String className = CSharpUtil.formatClassName(msgToken.name());

            try (final Writer out = outputManager.createOutput(className))
            {
                out.append(generateFileHeader(ir.packageName()));
                out.append(generateClassDeclaration(className, MESSAGE_FLYWEIGHT_TYPE));
                out.append(generateMessageFlyweightCode(className, msgToken.size(), msgToken.version(), msgToken.schemaId()));

                final List<Token> messageBody = tokens.subList(1, tokens.size() - 1);
                int offset = 0;

                final List<Token> rootFields = new ArrayList<>();
                offset = collectRootFields(messageBody, offset, rootFields);
                out.append(generateFields(className, rootFields, BASE_INDENT));

                final List<Token> groups = new ArrayList<>();
                offset = collectGroups(messageBody, offset, groups);
                final StringBuilder sb = new StringBuilder();
                generateGroups(sb, groups, 0, BASE_INDENT);
                out.append(sb);

                final List<Token> varData = messageBody.subList(offset, messageBody.size());
                out.append(generateVarData(varData));

                out.append("}\n");
            }
        }
    }

    private int collectRootFields(final List<Token> tokens, int index, final List<Token> rootFields)
    {
        for (int size = tokens.size(); index < size; index++)
        {
            final Token token = tokens.get(index);
            if (Signal.BEGIN_GROUP == token.signal() ||
                    Signal.END_GROUP == token.signal() ||
                    Signal.BEGIN_VAR_DATA == token.signal())
            {
                return index;
            }

            rootFields.add(token);
        }

        return index;
    }

    private int collectGroups(final List<Token> tokens, int index, final List<Token> groups)
    {
        for (int size = tokens.size(); index < size; index++)
        {
            final Token token = tokens.get(index);
            if (Signal.BEGIN_VAR_DATA == token.signal())
            {
                return index;
            }

            groups.add(token);
        }

        return index;
    }

    private int generateGroups(final StringBuilder sb, final List<Token> tokens, int index, final String indent)
    {
        for (int size = tokens.size(); index < size; index++)
        {
            if (tokens.get(index).signal() == Signal.BEGIN_GROUP)
            {
                final Token groupToken = tokens.get(index);
                final String groupName = groupToken.name();
                sb.append(generateGroupProperty(groupName, groupToken, indent));

                generateGroupClassHeader(sb, groupName, tokens, index, indent + INDENT);

                final List<Token> rootFields = new ArrayList<>();
                index = collectRootFields(tokens, ++index, rootFields);
                sb.append(generateFields(groupName, rootFields, indent + INDENT));

                if (tokens.get(index).signal() == Signal.BEGIN_GROUP)
                {
                    index = generateGroups(sb, tokens, index, indent + INDENT);
                }

                sb.append(indent).append("    }\n");
            }
        }

        return index;
    }

    // TODO port
    private void generateGroupClassHeader(final StringBuilder sb,
                                          final String groupName,
                                          final List<Token> tokens,
                                          final int index,
                                          final String indent)
    {
        final String dimensionsClassName = CSharpUtil.formatClassName(tokens.get(index + 1).name());
        final Integer dimensionHeaderSize = Integer.valueOf(tokens.get(index + 1).size());

        sb.append(String.format(
                "\n" +
                        indent + "public static class %s implements GroupFlyweight<%s>\n" +
                        indent + "{\n" +
                        indent + "    private final %s dimensions = new %s();\n" +
                        indent + "    private MessageFlyweight parentMessage;\n" +
                        indent + "    private DirectBuffer buffer;\n" +
                        indent + "    private int blockLength;\n" +
                        indent + "    private int actingVersion;\n" +
                        indent + "    private int count;\n" +
                        indent + "    private int index;\n" +
                        indent + "    private int offset;\n\n",
                CSharpUtil.formatClassName(groupName),
                CSharpUtil.formatClassName(groupName),
                dimensionsClassName,
                dimensionsClassName
        ));

        sb.append(String.format(
            indent + "    public void resetForDecode(final MessageFlyweight parentMessage, final DirectBuffer buffer, final int actingVersion)\n" +
                    indent + "    {\n" +
                    indent + "        this.parentMessage = parentMessage;\n" +
                    indent + "        this.buffer = buffer;\n" +
                    indent + "        dimensions.reset(buffer, parentMessage.position(), actingVersion);\n" +
                    indent + "        count = dimensions.numInGroup();\n" +
                    indent + "        blockLength = dimensions.blockLength();\n" +
                    indent + "        this.actingVersion = actingVersion;\n" +
                    indent + "        index = -1;\n" +
                    indent + "        final int dimensionsHeaderSize = %d;\n" +
                    indent + "        parentMessage.position(parentMessage.position() + dimensionsHeaderSize);\n" +
                    indent + "    }\n\n",
            dimensionHeaderSize
        ));

        final Integer blockLength = Integer.valueOf(tokens.get(index).size());
        final String javaTypeForBlockLength = cSharpTypeName(tokens.get(index + 2).encoding().primitiveType());
        final String javaTypeForNumInGroup = cSharpTypeName(tokens.get(index + 3).encoding().primitiveType());

        sb.append(String.format(
                indent + "    public void resetForEncode(final MessageFlyweight parentMessage, final DirectBuffer buffer, final int count)\n" +
                        indent + "    {\n" +
                        indent + "        this.parentMessage = parentMessage;\n" +
                        indent + "        this.buffer = buffer;\n" +
                        indent + "        dimensions.reset(buffer, parentMessage.position(), actingVersion);\n" +
                        indent + "        dimensions.numInGroup((%s)count);\n" +
                        indent + "        dimensions.blockLength((%s)%d);\n" +
                        indent + "        index = -1;\n" +
                        indent + "        this.count = count;\n" +
                        indent + "        blockLength = %d;\n" +
                        indent + "        final int dimensionsHeaderSize = %d;\n" +
                        indent + "        parentMessage.position(parentMessage.position() + dimensionsHeaderSize);\n" +
                        indent + "    }\n\n",
                javaTypeForNumInGroup,
                javaTypeForBlockLength,
                blockLength,
                blockLength,
                dimensionHeaderSize
        ));

        sb.append(String.format(
                indent + "    public int count()\n" +
                        indent + "    {\n" +
                        indent + "        return count;\n" +
                        indent + "    }\n\n" +
                        indent + "    public java.util.Iterator<%s> iterator()\n" +
                        indent + "    {\n" +
                        indent + "        return this;\n" +
                        indent + "    }\n\n" +
                        indent + "    public void remove()\n" +
                        indent + "    {\n" +
                        indent + "        throw new UnsupportedOperationException();\n" +
                        indent + "    }\n\n" +
                        indent + "    public boolean hasNext()\n" +
                        indent + "    {\n" +
                        indent + "        return index + 1 < count;\n" +
                        indent + "    }\n\n",
                CSharpUtil.formatClassName(groupName)
        ));

        sb.append(String.format(
                indent + "    public %s next()\n" +
                        indent + "    {\n" +
                        indent + "        if (index + 1 >= count)\n" +
                        indent + "        {\n" +
                        indent + "            throw new java.util.NoSuchElementException();\n" +
                        indent + "        }\n\n" +
                        indent + "        offset = parentMessage.position();\n" +
                        indent + "        parentMessage.position(offset + blockLength);\n" +
                        indent + "        ++index;\n\n" +
                        indent + "        return this;\n" +
                        indent + "    }\n",
                CSharpUtil.formatClassName(groupName)
        ));
    }

    // TODO port
    private CharSequence generateGroupProperty(final String groupName, final Token token, final String indent)
    {
        final StringBuilder sb = new StringBuilder();

        final String className = CSharpUtil.formatClassName(groupName);
        final String propertyName = CSharpUtil.formatPropertyName(groupName);

        sb.append(String.format(
                "\n" +
                        indent + "    private final %s %s = new %s();\n",
                className,
                propertyName,
                className
        ));

        sb.append(String.format(
                "\n" +
                        indent + "    public static long %sSchemaId()\n" +
                        indent + "    {\n" +
                        indent + "        return %d;\n" +
                        indent + "    }\n\n",
                groupName,
                Integer.valueOf(token.schemaId())
        ));

        sb.append(String.format(
                "\n" +
                        indent + "    public %s %s()\n" +
                        indent + "    {\n" +
                        indent + "        %s.resetForDecode(parentMessage, buffer, actingVersion);\n" +
                        indent + "        return %s;\n" +
                        indent + "    }\n",
                className,
                propertyName,
                propertyName,
                propertyName
        ));

        sb.append(String.format(
                "\n" +
                        indent + "    public %s %sCount(final int count)\n" +
                        indent + "    {\n" +
                        indent + "        %s.resetForEncode(parentMessage, buffer, count);\n" +
                        indent + "        return %s;\n" +
                        indent + "    }\n",
                className,
                propertyName,
                propertyName,
                propertyName
        ));

        return sb;
    }

    // TODO port
    private CharSequence generateVarData(final List<Token> tokens)
    {
        final StringBuilder sb = new StringBuilder();

        for (int i = 0, size = tokens.size(); i < size; i++)
        {
            final Token token = tokens.get(i);
            if (token.signal() == Signal.BEGIN_VAR_DATA)
            {
                generateFieldIdMethod(sb, token, BASE_INDENT);

                final String characterEncoding = tokens.get(i + 3).encoding().characterEncoding();
                generateCharacterEncodingMethod(sb, token.name(), characterEncoding);

                final String propertyName = CSharpUtil.toUpperFirstChar(token.name());
                final Token lengthToken = tokens.get(i + 2);
                final Integer sizeOfLengthField = Integer.valueOf(lengthToken.size());
                final Encoding lengthEncoding = lengthToken.encoding();
                final String lengthJavaType = cSharpTypeName(lengthEncoding.primitiveType());
                final String lengthTypePrefix = lengthEncoding.primitiveType().primitiveName();
                final ByteOrder byteOrder = lengthEncoding.byteOrder();
                final String byteOrderStr = lengthEncoding.primitiveType().size() == 1 ? "" : ", java.nio.ByteOrder." + byteOrder;

                sb.append(String.format(
                        "    public int get%s(final byte[] dst, final int dstOffset, final int length)\n" +
                                "    {\n" +
                                "%s" +
                                "        final int sizeOfLengthField = %d;\n" +
                                "        final int lengthPosition = position();\n" +
                                "        position(lengthPosition + sizeOfLengthField);\n" +
                                "        final int dataLength = CodecUtil.%sGet(buffer, lengthPosition%s);\n" +
                                "        final int bytesCopied = Math.min(length, dataLength);\n" +
                                "        CodecUtil.int8sGet(buffer, position(), dst, dstOffset, bytesCopied);\n" +
                                "        position(position() + dataLength);\n\n" +
                                "        return bytesCopied;\n" +
                                "    }\n\n",
                        propertyName,
                        generateArrayFieldNotPresentCondition(token.version(), BASE_INDENT),
                        sizeOfLengthField,
                        lengthTypePrefix,
                        byteOrderStr
                ));

                sb.append(String.format(
                        "    public int put%s(final byte[] src, final int srcOffset, final int length)\n" +
                                "    {\n" +
                                "        final int sizeOfLengthField = %d;\n" +
                                "        final int lengthPosition = position();\n" +
                                "        CodecUtil.%sPut(buffer, lengthPosition, (%s)length%s);\n" +
                                "        position(lengthPosition + sizeOfLengthField);\n" +
                                "        CodecUtil.int8sPut(buffer, position(), src, srcOffset, length);\n" +
                                "        position(position() + length);\n\n" +
                                "        return length;\n" +
                                "    }\n",
                        propertyName,
                        sizeOfLengthField,
                        lengthTypePrefix,
                        lengthJavaType,
                        byteOrderStr
                ));
            }
        }

        return sb;
    }

    // TODO port
    private void generateBitSet(final List<Token> tokens) throws IOException
    {
        final String bitSetName = CSharpUtil.formatClassName(tokens.get(0).name());

        try (final Writer out = outputManager.createOutput(bitSetName))
        {
            out.append(generateFileHeader(ir.packageName()));
            out.append(generateClassDeclaration(bitSetName, FIXED_FLYWEIGHT_TYPE));
            out.append(generateFixedFlyweightCode(tokens.get(0).size()));

            out.append(generateChoices(bitSetName, tokens.subList(1, tokens.size() - 1)));

            out.append("    }\n");
            out.append("}\n");
        }
    }

    private void generateEnum(final List<Token> tokens) throws IOException
    {
        Token enumToken = tokens.get(0);
        final String enumName = CSharpUtil.formatClassName(enumToken.name());

        try (final Writer out = outputManager.createOutput(enumName))
        {
            out.append(generateFileHeader(ir.packageName()));
            String enumPrimitiveType = cSharpTypeName(enumToken.encoding().primitiveType());
            out.append(generateEnumDeclaration(enumName, enumPrimitiveType));

            out.append(generateEnumValues(tokens.subList(1, tokens.size() - 1), enumToken));

            out.append(INDENT).append("}\n");

            out.append("}\n");
        }
    }

    // TODO port
    private void generateComposite(final List<Token> tokens) throws IOException
    {
        final String compositeName = CSharpUtil.formatClassName(tokens.get(0).name());

        try (final Writer out = outputManager.createOutput(compositeName))
        {
            out.append(generateFileHeader(ir.packageName()));
            out.append(generateClassDeclaration(compositeName, FIXED_FLYWEIGHT_TYPE));
            out.append(generateFixedFlyweightCode(tokens.get(0).size()));

            out.append(generatePrimitivePropertyEncodings(compositeName, tokens.subList(1, tokens.size() - 1), BASE_INDENT));

            out.append("}\n");
        }
    }

    private CharSequence generateChoiceNotPresentCondition(final int sinceVersion, final String indent)
    {
        if (0 == sinceVersion)
        {
            return "";
        }

        return String.format(
                indent + "            if (_actingVersion < %d) return false;\n",
                Integer.valueOf(sinceVersion)
        );
    }

    private CharSequence generateChoices(final String bitsetClassName, final List<Token> tokens)
    {
        final StringBuilder sb = new StringBuilder();

        for (final Token token : tokens)
        {
            if (token.signal() == Signal.CHOICE)
            {
                final String choiceName = toUpperFirstChar(token.name());
                final String typeName = cSharpTypeName(token.encoding().primitiveType());
                final String choiceBitPosition = token.encoding().constVal().toString();

                sb.append(String.format(
                        "\n" +
                        "        bool %s \n" +
                        "        {\n" +
                        "            get\n" +
                        "            {\n" +
                        "%s" +
                        "                return (%s*((%s *)(_pBuffer + _offset))) & (0x1UL << %s)) == 1;\n" +
                        "            }\n" +
                        "        }\n\n",
                        choiceName,
                        generateChoiceNotPresentCondition(token.version(), BASE_INDENT + BASE_INDENT),
                        formatByteOrderEncoding(token.encoding().byteOrder(), token.encoding().primitiveType()),
                        typeName,
                        choiceBitPosition
                ));

                sb.append(String.format(
                        "        public %s Set%s(bool value)\n" +
                        "        {\n" +
                        "            *((%s *)(_pBuffer + _offset)) |= (%s)%s*(ulong*)&value << %s);\n" +
                        "            return this;\n" +
                        "        }\n",
                        bitsetClassName,
                        choiceName,
                        typeName,
                        typeName,
                        formatByteOrderEncoding(token.encoding().byteOrder(), token.encoding().primitiveType()),
                        choiceBitPosition
                ));
            }
        }

        return sb;
    }

    private CharSequence generateEnumValues(final List<Token> tokens, final Token encodingToken)
    {
        final StringBuilder sb = new StringBuilder();
        final Encoding encoding = encodingToken.encoding();

        for (final Token token : tokens)
        {
            sb.append(INDENT).append(INDENT).append(token.name()).append(" = ").append(token.encoding().constVal()).append(",\n");
        }

        final PrimitiveValue nullVal = (encoding.nullVal() != null) ? encoding.nullVal() : encoding.primitiveType().nullVal();

        sb.append(INDENT).append(INDENT).append("NULL_VALUE = ").append(nullVal).append("\n");

        return sb;
    }

    // TODO port
    private CharSequence generateFileHeader(final String packageName)
    {
        // TODO ODE: uppercase first letters of namespace
        return String.format(
                "/* Generated SBE (Simple Binary Encoding) message codec */\n\n" +
                        "using Adaptive.SimpleBinaryEncoding;\n\n" +
                        "namespace %s\n" +
                        "{\n",
                packageName
        );
    }

    private CharSequence generateClassDeclaration(final String className, final String implementedInterface)
    {
        return String.format(
                "    public unsafe class %s : %s\n" +
                        "    {\n",
                className,
                implementedInterface
        );
    }

    private CharSequence generateEnumDeclaration(final String name, final String primitiveType)
    {
        return INDENT + "public enum " + name + " : " + primitiveType + "\n" +
               INDENT + "{\n";
    }

    // TODO port
    private CharSequence generatePrimitivePropertyEncodings(final String containingClassName,
                                                            final List<Token> tokens,
                                                            final String indent)
    {
        final StringBuilder sb = new StringBuilder();

        for (final Token token : tokens)
        {
            if (token.signal() == Signal.ENCODING)
            {
                sb.append(generatePrimitiveProperty(containingClassName, token.name(), token, indent));
            }
        }

        return sb;
    }

    // TODO port
    private CharSequence generatePrimitiveProperty(final String containingClassName,
                                                   final String propertyName,
                                                   final Token token,
                                                   final String indent)
    {
        if (Encoding.Presence.CONSTANT == token.encoding().presence())
        {
            return generateConstPropertyMethods(propertyName, token, indent);
        }
        else
        {
            return generatePrimitivePropertyMethods(containingClassName, propertyName, token, indent);
        }
    }

    // TODO port
    private CharSequence generatePrimitivePropertyMethods(final String containingClassName,
                                                          final String propertyName,
                                                          final Token token,
                                                          final String indent)
    {
        final int arrayLength = token.arrayLength();

        if (arrayLength == 1)
        {
            return generateSingleValueProperty(containingClassName, propertyName, token, indent);
        }
        else if (arrayLength > 1)
        {
            return generateArrayProperty(containingClassName, propertyName, token, indent);
        }

        return "";
    }

    // TODO port
    private CharSequence generateSingleValueProperty(final String containingClassName,
                                                     final String propertyName,
                                                     final Token token,
                                                     final String indent)
    {
        final String javaTypeName = cSharpTypeName(token.encoding().primitiveType());
        final String typePrefix = token.encoding().primitiveType().primitiveName();
        final Integer offset = Integer.valueOf(token.offset());
        final ByteOrder byteOrder = token.encoding().byteOrder();
        final String byteOrderStr = token.encoding().primitiveType().size() == 1 ? "" : ", java.nio.ByteOrder." + byteOrder;

        final StringBuilder sb = new StringBuilder();

        sb.append(String.format(
                "\n" +
                        indent + "    public %s %s()\n" +
                        indent + "    {\n" +
                        "%s" +
                        indent + "        return CodecUtil.%sGet(buffer, offset + %d%s);\n" +
                        indent + "    }\n\n",
                javaTypeName,
                propertyName,
                generateFieldNotPresentCondition(token.version(), token.encoding(), indent),
                typePrefix,
                offset,
                byteOrderStr
        ));

        sb.append(String.format(
                indent + "    public %s %s(final %s value)\n" +
                        indent + "    {\n" +
                        indent + "        CodecUtil.%sPut(buffer, offset + %d, value%s);\n" +
                        indent + "        return this;\n" +
                        indent + "    }\n",
                CSharpUtil.formatClassName(containingClassName),
                propertyName,
                javaTypeName,
                typePrefix,
                offset,
                byteOrderStr
        ));

        return sb;
    }

    // TODO port
    private CharSequence generateFieldNotPresentCondition(final int sinceVersion, final Encoding encoding, final String indent)
    {
        if (0 == sinceVersion)
        {
            return "";
        }

        return String.format(
                indent + "        if (actingVersion < %d)\n" +
                        indent + "        {\n" +
                        indent + "            return %s;\n" +
                        indent + "        }\n\n",
                Integer.valueOf(sinceVersion),
                sinceVersion > 0 ? generateLiteral(encoding.primitiveType(), encoding.nullVal()) : "(byte)0"
        );
    }

    // TODO port
    private CharSequence generateArrayFieldNotPresentCondition(final int sinceVersion, final String indent)
    {
        if (0 == sinceVersion)
        {
            return "";
        }

        return String.format(
                indent + "        if (actingVersion < %d)\n" +
                        indent + "        {\n" +
                        indent + "            return 0;\n" +
                        indent + "        }\n\n",
                Integer.valueOf(sinceVersion)
        );
    }

    // TODO port
    private CharSequence generateTypeFieldNotPresentCondition(final int sinceVersion, final String indent)
    {
        if (0 == sinceVersion)
        {
            return "";
        }

        return String.format(
                indent + "        if (actingVersion < %d)\n" +
                        indent + "        {\n" +
                        indent + "            return null;\n" +
                        indent + "        }\n\n",
                Integer.valueOf(sinceVersion)
        );
    }

    // TODO port
    private CharSequence generateArrayProperty(final String containingClassName,
                                               final String propertyName,
                                               final Token token,
                                               final String indent)
    {
        final String javaTypeName = cSharpTypeName(token.encoding().primitiveType());
        final String typePrefix = token.encoding().primitiveType().primitiveName();
        final Integer offset = Integer.valueOf(token.offset());
        final ByteOrder byteOrder = token.encoding().byteOrder();
        final String byteOrderStr = token.encoding().primitiveType().size() == 1 ? "" : ", java.nio.ByteOrder." + byteOrder;
        final Integer fieldLength = Integer.valueOf(token.arrayLength());
        final Integer typeSize = Integer.valueOf(token.encoding().primitiveType().size());

        final StringBuilder sb = new StringBuilder();

        sb.append(String.format(
                "\n" +
                        indent + "    public static int %sLength()\n" +
                        indent + "    {\n" +
                        indent + "        return %d;\n" +
                        indent + "    }\n\n",
                propertyName,
                fieldLength
        ));

        sb.append(String.format(
                indent + "    public %s %s(final int index)\n" +
                        indent + "    {\n" +
                        indent + "        if (index < 0 || index >= %d)\n" +
                        indent + "        {\n" +
                        indent + "            throw new IndexOutOfBoundsException(\"index out of range: index=\" + index);\n" +
                        indent + "        }\n\n" +
                        "%s" +
                        indent + "        return CodecUtil.%sGet(buffer, this.offset + %d + (index * %d)%s);\n" +
                        indent + "    }\n\n",
                javaTypeName,
                propertyName,
                fieldLength,
                generateFieldNotPresentCondition(token.version(), token.encoding(), indent),
                typePrefix,
                offset,
                typeSize,
                byteOrderStr
        ));

        sb.append(String.format(
                indent + "    public void %s(final int index, final %s value)\n" +
                        indent + "    {\n" +
                        indent + "        if (index < 0 || index >= %d)\n" +
                        indent + "        {\n" +
                        indent + "            throw new IndexOutOfBoundsException(\"index out of range: index=\" + index);\n" +
                        indent + "        }\n\n" +
                        indent + "        CodecUtil.%sPut(buffer, this.offset + %d + (index * %d), value%s);\n" +
                        indent + "    }\n",
                propertyName,
                javaTypeName,
                fieldLength,
                typePrefix,
                offset,
                typeSize,
                byteOrderStr
        ));

        if (token.encoding().primitiveType() == PrimitiveType.CHAR)
        {
            generateCharacterEncodingMethod(sb, propertyName, token.encoding().characterEncoding());

            sb.append(String.format(
                    "\n" +
                            indent + "    public int get%s(final byte[] dst, final int dstOffset)\n" +
                            indent + "    {\n" +
                            indent + "        final int length = %d;\n" +
                            indent + "        if (dstOffset < 0 || dstOffset > (dst.length - length))\n" +
                            indent + "        {\n" +
                            indent + "            throw new IndexOutOfBoundsException(\"dstOffset out of range for copy: offset=\" + dstOffset);\n" +
                            indent + "        }\n\n" +
                            "%s" +
                            indent + "        CodecUtil.charsGet(buffer, this.offset + %d, dst, dstOffset, length);\n" +
                            indent + "        return length;\n" +
                            indent + "    }\n\n",
                    CSharpUtil.toUpperFirstChar(propertyName),
                    fieldLength,
                    generateArrayFieldNotPresentCondition(token.version(), indent),
                    offset
            ));

            sb.append(String.format(
                    indent + "    public %s put%s(final byte[] src, final int srcOffset)\n" +
                            indent + "    {\n" +
                            indent + "        final int length = %d;\n" +
                            indent + "        if (srcOffset < 0 || srcOffset > (src.length - length))\n" +
                            indent + "        {\n" +
                            indent + "            throw new IndexOutOfBoundsException(\"srcOffset out of range for copy: offset=\" + srcOffset);\n" +
                            indent + "        }\n\n" +
                            indent + "        CodecUtil.charsPut(buffer, this.offset + %d, src, srcOffset, length);\n" +
                            indent + "        return this;\n" +
                            indent + "    }\n",
                    containingClassName,
                    CSharpUtil.toUpperFirstChar(propertyName),
                    fieldLength,
                    offset
            ));
        }

        return sb;
    }

    // TODO port
    private void generateCharacterEncodingMethod(final StringBuilder sb, final String propertyName, final String encoding)
    {
        sb.append(String.format(
                "\n" +
                        "    public static String %sCharacterEncoding()\n" +
                        "    {\n" +
                        "        return \"%s\";\n" +
                        "    }\n\n",
                CSharpUtil.formatPropertyName(propertyName),
                encoding
        ));
    }

    // TODO port
    private CharSequence generateConstPropertyMethods(final String propertyName, final Token token, final String indent)
    {
        if (token.encoding().primitiveType() != PrimitiveType.CHAR)
        {
            return String.format(
                    "\n" +
                            indent + "    public %s %s()\n" +
                            indent + "    {\n" +
                            indent + "        return %s;\n" +
                            indent + "    }\n",
                    cSharpTypeName(token.encoding().primitiveType()),
                    propertyName,
                    generateLiteral(token.encoding().primitiveType(), token.encoding().constVal())
            );
        }

        final StringBuilder sb = new StringBuilder();

        final String javaTypeName = cSharpTypeName(token.encoding().primitiveType());
        final byte[] constantValue = token.encoding().constVal().byteArrayValue();
        final CharSequence values = generateByteLiteralList(token.encoding().constVal().byteArrayValue());

        sb.append(String.format(
                "\n" +
                        indent + "    private static final byte[] %sValue = {%s};\n",
                propertyName,
                values
        ));

        sb.append(String.format(
                "\n" +
                        indent + "    public static int %sLength()\n" +
                        indent + "    {\n" +
                        indent + "        return %d;\n" +
                        indent + "    }\n\n",
                propertyName,
                Integer.valueOf(constantValue.length)
        ));

        sb.append(String.format(
                indent + "    public %s %s(final int index)\n" +
                        indent + "    {\n" +
                        indent + "        return %sValue[index];\n" +
                        indent + "    }\n\n",
                javaTypeName,
                propertyName,
                propertyName
        ));

        sb.append(String.format(
                indent + "    public int get%s(final byte[] dst, final int offset, final int length)\n" +
                        indent + "    {\n" +
                        indent + "        final int bytesCopied = Math.min(length, %d);\n" +
                        indent + "        System.arraycopy(%sValue, 0, dst, offset, bytesCopied);\n" +
                        indent + "        return bytesCopied;\n" +
                        indent + "    }\n",
                CSharpUtil.toUpperFirstChar(propertyName),
                Integer.valueOf(constantValue.length),
                propertyName
        ));

        return sb;
    }

    // TODO port
    private CharSequence generateByteLiteralList(final byte[] bytes)
    {
        final StringBuilder values = new StringBuilder();
        for (final byte b : bytes)
        {
            values.append(b).append(", ");
        }

        if (values.length() > 0)
        {
            values.setLength(values.length() - 2);
        }

        return values;
    }

    private CharSequence generateFixedFlyweightCode(final int size)
    {
        return String.format(
                "        private byte* _pBuffer;\n" +
                "        private int _offset;\n" +
                "        private int _actingVersion;\n\n" +
                "        public void Reset(DirectBuffer buffer, int offset, int actingVersion)\n" +
                "        {\n" +
                "            _pBuffer = buffer.BufferPtr;\n" +
                "            _offset = offset;\n" +
                "            _actingVersion = actingVersion;\n" +
                "        }\n\n" +
                "        public int Size { get { return %d; } }\n",
                Integer.valueOf(size)
        );
    }

    // TODO port
    private CharSequence generateMessageFlyweightCode(final String className,
                                                      final int blockLength,
                                                      final int version,
                                                      final int schemaId)
    {
        return String.format(
                "    public static final int TEMPLATE_ID = %d;\n" +
                        "    public static final int TEMPLATE_VERSION = %d;\n" +
                        "    public static final int BLOCK_LENGTH = %d;\n\n" +
                        "    private MessageFlyweight parentMessage = this;\n" +
                        "    private DirectBuffer buffer;\n" +
                        "    private int offset;\n" +
                        "    private int position;\n" +
                        "    private int actingBlockLength;\n" +
                        "    private int actingVersion;\n" +
                        "\n" +
                        "    public int blockLength()\n" +
                        "    {\n" +
                        "        return BLOCK_LENGTH;\n" +
                        "    }\n\n" +
                        "    public int templateId()\n" +
                        "    {\n" +
                        "        return TEMPLATE_ID;\n" +
                        "    }\n\n" +
                        "    public int templateVersion()\n" +
                        "    {\n" +
                        "        return TEMPLATE_VERSION;\n" +
                        "    }\n\n" +
                        "    public int offset()\n" +
                        "    {\n" +
                        "        return offset;\n" +
                        "    }\n\n" +
                        "    public %s resetForEncode(final DirectBuffer buffer, final int offset)\n" +
                        "    {\n" +
                        "        this.buffer = buffer;\n" +
                        "        this.offset = offset;\n" +
                        "        this.actingBlockLength = BLOCK_LENGTH;\n" +
                        "        this.actingVersion = TEMPLATE_VERSION;\n" +
                        "        position(offset + actingBlockLength);\n\n" +
                        "        return this;\n" +
                        "    }\n\n" +
                        "    public %s resetForDecode(final DirectBuffer buffer, final int offset,\n" +
                        "                             final int actingBlockLength, final int actingVersion)\n" +
                        "    {\n" +
                        "        this.buffer = buffer;\n" +
                        "        this.offset = offset;\n" +
                        "        this.actingBlockLength = actingBlockLength;\n" +
                        "        this.actingVersion = actingVersion;\n" +
                        "        position(offset + actingBlockLength);\n\n" +
                        "        return this;\n" +
                        "    }\n\n" +
                        "    public int size()\n" +
                        "    {\n" +
                        "        return position - offset;\n" +
                        "    }\n\n" +
                        "    public int position()\n" +
                        "    {\n" +
                        "        return position;\n" +
                        "    }\n\n" +
                        "    public void position(final int position)\n" +
                        "    {\n" +
                        "        CodecUtil.checkPosition(position, buffer.capacity());\n" +
                        "        this.position = position;\n" +
                        "    }\n",
                Integer.valueOf(schemaId),
                Integer.valueOf(version),
                Integer.valueOf(blockLength),
                className,
                className
        );
    }

    // TODO port
    private CharSequence generateFields(final String containingClassName, final List<Token> tokens, final String indent)
    {
        final StringBuilder sb = new StringBuilder();

        for (int i = 0, size = tokens.size(); i < size; i++)
        {
            final Token signalToken = tokens.get(i);
            if (signalToken.signal() == Signal.BEGIN_FIELD)
            {
                final Token encodingToken = tokens.get(i + 1);
                final String propertyName = signalToken.name();

                generateFieldIdMethod(sb, signalToken, indent);

                switch (encodingToken.signal())
                {
                    case ENCODING:
                        sb.append(generatePrimitiveProperty(containingClassName, propertyName, encodingToken, indent));
                        break;

                    case BEGIN_ENUM:
                        sb.append(generateEnumProperty(containingClassName, propertyName, encodingToken, indent));
                        break;

                    case BEGIN_SET:
                        sb.append(generateBitSetProperty(propertyName, encodingToken, indent));
                        break;

                    case BEGIN_COMPOSITE:
                        sb.append(generateCompositeProperty(propertyName, encodingToken, indent));
                        break;
                }
            }
        }

        return sb;
    }

    // TODO port
    private void generateFieldIdMethod(final StringBuilder sb, final Token token, final String indent)
    {
        sb.append(String.format(
                "\n" +
                        indent + "    public static int %sSchemaId()\n" +
                        indent + "    {\n" +
                        indent + "        return %d;\n" +
                        indent + "    }\n",
                CSharpUtil.formatPropertyName(token.name()),
                Integer.valueOf(token.schemaId())
        ));
    }

    // TODO port
    private CharSequence generateEnumProperty(final String containingClassName,
                                              final String propertyName,
                                              final Token token,
                                              final String indent)
    {
        final String enumName = token.name();
        final String typePrefix = token.encoding().primitiveType().primitiveName();
        final Integer offset = Integer.valueOf(token.offset());
        final ByteOrder byteOrder = token.encoding().byteOrder();
        final String byteOrderStr = token.encoding().primitiveType().size() == 1 ? "" : ", java.nio.ByteOrder." + byteOrder;

        final StringBuilder sb = new StringBuilder();

        sb.append(String.format(
                "\n" +
                        indent + "    public %s %s()\n" +
                        indent + "    {\n" +
                        "%s" +
                        indent + "        return %s.get(CodecUtil.%sGet(buffer, offset + %d%s));\n" +
                        indent + "    }\n\n",
                enumName,
                propertyName,
                generateTypeFieldNotPresentCondition(token.version(), indent),
                enumName,
                typePrefix,
                offset,
                byteOrderStr
        ));

        sb.append(String.format(
                indent + "    public %s %s(final %s value)\n" +
                        indent + "    {\n" +
                        indent + "        CodecUtil.%sPut(buffer, offset + %d, value.value()%s);\n" +
                        indent + "        return this;\n" +
                        indent + "    }\n",
                CSharpUtil.formatClassName(containingClassName),
                propertyName,
                enumName,
                typePrefix,
                offset,
                byteOrderStr
        ));

        return sb;
    }

    // TODO port
    private Object generateBitSetProperty(final String propertyName, final Token token, final String indent)
    {
        final StringBuilder sb = new StringBuilder();

        final String bitSetName = CSharpUtil.formatClassName(token.name());
        final String formattedPropertyName = CSharpUtil.formatPropertyName(propertyName);
        final Integer offset = Integer.valueOf(token.offset());

        sb.append(String.format(
                "\n" +
                        indent + "    private final %s %s = new %s();\n",
                bitSetName,
                formattedPropertyName,
                bitSetName
        ));

        sb.append(String.format(
                "\n" +
                        indent + "    public %s %s()\n" +
                        indent + "    {\n" +
                        "%s" +
                        indent + "        %s.reset(buffer, offset + %d, actingVersion);\n" +
                        indent + "        return %s;\n" +
                        indent + "    }\n",
                bitSetName,
                formattedPropertyName,
                generateTypeFieldNotPresentCondition(token.version(), indent),
                formattedPropertyName,
                offset,
                formattedPropertyName
        ));

        return sb;
    }

    // TODO port
    private Object generateCompositeProperty(final String propertyName, final Token token, final String indent)
    {
        final String compositeName = CSharpUtil.formatClassName(token.name());
        final Integer offset = Integer.valueOf(token.offset());

        final StringBuilder sb = new StringBuilder();

        sb.append(String.format(
                "\n" +
                        indent + "    private final %s %s = new %s();\n",
                compositeName,
                propertyName,
                compositeName
        ));

        sb.append(String.format(
                "\n" +
                        indent + "    public %s %s()\n" +
                        indent + "    {\n" +
                        "%s" +
                        indent + "        %s.reset(buffer, offset + %d, actingVersion);\n" +
                        indent + "        return %s;\n" +
                        indent + "    }\n",
                compositeName,
                propertyName,
                generateTypeFieldNotPresentCondition(token.version(), indent),
                propertyName,
                offset,
                propertyName
        ));

        return sb;
    }

    // TODO port
    private String generateLiteral(final PrimitiveType type, final PrimitiveValue value)
    {
        String literal = "";

        final String castType = cSharpTypeName(type);
        switch (type)
        {
            case CHAR:
            case UINT8:
            case UINT16:
            case INT8:
            case INT16:
                literal = "(" + castType + ")" + value;
                break;

            case UINT32:
            case INT32:
                literal = value.toString();
                break;

            case FLOAT:
                literal = value + "f";
                break;

            case UINT64:
            case INT64:
                literal = value + "L";
                break;

            case DOUBLE:
                literal = value + "d";
        }

        return literal;
    }
}
