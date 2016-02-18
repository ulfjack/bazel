// Copyright 2014 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.syntax;

import com.google.common.base.CharMatcher;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.syntax.ClassObject.SkylarkClassObject;
import com.google.devtools.build.lib.syntax.SkylarkList.MutableList;
import com.google.devtools.build.lib.syntax.SkylarkList.Tuple;
import com.google.devtools.build.lib.syntax.SkylarkSignature.Param;
import com.google.devtools.build.lib.syntax.Type.ConversionException;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A helper class containing built in functions for the Build and the Build Extension Language.
 */
public class MethodLibrary {

  private MethodLibrary() {}

  // Convert string index in the same way Python does.
  // If index is negative, starts from the end.
  // If index is outside bounds, it is restricted to the valid range.
  private static int clampIndex(int index, int length) {
    if (index < 0) {
      index += length;
    }
    return Math.max(Math.min(index, length), 0);
  }

  // Emulate Python substring function
  // It converts out of range indices, and never fails
  private static String pythonSubstring(String str, int start, Object end, String msg)
      throws ConversionException {
    if (start == 0 && EvalUtils.isNullOrNone(end)) {
      return str;
    }
    start = clampIndex(start, str.length());
    int stop;
    if (EvalUtils.isNullOrNone(end)) {
      stop = str.length();
    } else {
      stop = clampIndex(Type.INTEGER.convert(end, msg), str.length());
    }
    if (start >= stop) {
      return "";
    }
    return str.substring(start, stop);
  }

  private static int getListIndex(Object key, int listSize, Location loc)
      throws ConversionException, EvalException {
    // Get the nth element in the list
    int index = Type.INTEGER.convert(key, "index operand");
    if (index < 0) {
      index += listSize;
    }
    if (index < 0 || index >= listSize) {
      throw new EvalException(loc, "List index out of range (index is "
          + index + ", but list has " + listSize + " elements)");
    }
    return index;
  }

  // supported string methods

  @SkylarkSignature(name = "join", objectType = StringModule.class, returnType = String.class,
      doc = "Returns a string in which the string elements of the argument have been "
          + "joined by this string as a separator. Example:<br>"
          + "<pre class=\"language-python\">\"|\".join([\"a\", \"b\", \"c\"]) == \"a|b|c\"</pre>",
      mandatoryPositionals = {
        @Param(name = "self", type = String.class, doc = "This string, a separator."),
        @Param(name = "elements", type = SkylarkList.class, doc = "The objects to join.")})
  private static BuiltinFunction join = new BuiltinFunction("join") {
    public String invoke(String self, SkylarkList elements) throws ConversionException {
      return Joiner.on(self).join(elements);
    }
  };

  @SkylarkSignature(name = "lower", objectType = StringModule.class, returnType = String.class,
      doc = "Returns the lower case version of this string.",
      mandatoryPositionals = {
        @Param(name = "self", type = String.class, doc = "This string, to convert to lower case.")})
  private static BuiltinFunction lower = new BuiltinFunction("lower") {
    public String invoke(String self) {
      return self.toLowerCase();
    }
  };

  @SkylarkSignature(name = "upper", objectType = StringModule.class, returnType = String.class,
      doc = "Returns the upper case version of this string.",
      mandatoryPositionals = {
        @Param(name = "self", type = String.class, doc = "This string, to convert to upper case.")})
  private static BuiltinFunction upper = new BuiltinFunction("upper") {
    public String invoke(String self) {
      return self.toUpperCase();
    }
  };

  private static String stringLStrip(String self, String chars) {
    CharMatcher matcher = CharMatcher.anyOf(chars);
    for (int i = 0; i < self.length(); i++) {
      if (!matcher.matches(self.charAt(i))) {
        return self.substring(i);
      }
    }
    return ""; // All characters were stripped.
  }

  private static String stringRStrip(String self, String chars) {
    CharMatcher matcher = CharMatcher.anyOf(chars);
    for (int i = self.length() - 1; i >= 0; i--) {
      if (!matcher.matches(self.charAt(i))) {
        return self.substring(0, i + 1);
      }
    }
    return ""; // All characters were stripped.
  }

  @SkylarkSignature(
    name = "lstrip",
    objectType = StringModule.class,
    returnType = String.class,
    doc =
        "Returns a copy of the string where leading characters that appear in <code>chars</code>"
            + "are removed."
            + "<pre class=\"language-python\">"
            + "\"abcba\".lstrip(\"ba\") == \"cba\""
            + "</pre",
    mandatoryPositionals = {
      @Param(name = "self", type = String.class, doc = "This string"),
    },
    optionalPositionals = {
      @Param(
        name = "chars",
        type = String.class,
        doc = "The characters to remove",
        defaultValue = "' \\t\\n\\r'"  // \f \v are illegal in Skylark
      )
    }
  )
  private static BuiltinFunction lstrip =
      new BuiltinFunction("lstrip") {
        public String invoke(String self, String chars) {
          return stringLStrip(self, chars);
        }
      };

  @SkylarkSignature(
    name = "rstrip",
    objectType = StringModule.class,
    returnType = String.class,
    doc =
        "Returns a copy of the string where trailing characters that appear in <code>chars</code>"
            + "are removed."
            + "<pre class=\"language-python\">"
            + "\"abcba\".rstrip(\"ba\") == \"abc\""
            + "</pre",
    mandatoryPositionals = {
      @Param(name = "self", type = String.class, doc = "This string"),
    },
    optionalPositionals = {
      @Param(
        name = "chars",
        type = String.class,
        doc = "The characters to remove",
        defaultValue = "' \\t\\n\\r'"  // \f \v are illegal in Skylark
      )
    }
  )
  private static BuiltinFunction rstrip =
      new BuiltinFunction("rstrip") {
        public String invoke(String self, String chars) {
          return stringRStrip(self, chars);
        }
      };

  @SkylarkSignature(
    name = "strip",
    objectType = StringModule.class,
    returnType = String.class,
    doc =
        "Returns a copy of the string where trailing characters that appear in <code>chars</code>"
            + "are removed."
            + "<pre class=\"language-python\">"
            + "\"abcba\".strip(\"ba\") == \"abc\""
            + "</pre",
    mandatoryPositionals = {
      @Param(name = "self", type = String.class, doc = "This string"),
    },
    optionalPositionals = {
      @Param(
        name = "chars",
        type = String.class,
        doc = "The characters to remove",
        defaultValue = "' \\t\\n\\r'"  // \f \v are illegal in Skylark
      )
    }
  )
  private static BuiltinFunction strip =
      new BuiltinFunction("strip") {
        public String invoke(String self, String chars) {
          return stringLStrip(stringRStrip(self, chars), chars);
        }
      };

  @SkylarkSignature(name = "replace", objectType = StringModule.class, returnType = String.class,
      doc = "Returns a copy of the string in which the occurrences "
          + "of <code>old</code> have been replaced with <code>new</code>, optionally restricting "
          + "the number of replacements to <code>maxsplit</code>.",
      mandatoryPositionals = {
        @Param(name = "self", type = String.class, doc = "This string."),
        @Param(name = "old", type = String.class, doc = "The string to be replaced."),
        @Param(name = "new", type = String.class, doc = "The string to replace with.")},
      optionalPositionals = {
        @Param(name = "maxsplit", type = Integer.class, noneable = true, defaultValue = "None",
            doc = "The maximum number of replacements.")},
      useLocation = true)
  private static BuiltinFunction replace = new BuiltinFunction("replace") {
    public String invoke(String self, String oldString, String newString, Object maxSplitO,
        Location loc) throws EvalException, ConversionException {
      StringBuffer sb = new StringBuffer();
      Integer maxSplit = Type.INTEGER.convertOptional(
          maxSplitO, "'maxsplit' argument of 'replace'", /*label*/null, Integer.MAX_VALUE);
      try {
        Matcher m = Pattern.compile(oldString, Pattern.LITERAL).matcher(self);
        for (int i = 0; i < maxSplit && m.find(); i++) {
          m.appendReplacement(sb, Matcher.quoteReplacement(newString));
        }
        m.appendTail(sb);
      } catch (IllegalStateException e) {
        throw new EvalException(loc, e.getMessage() + " in call to replace");
      }
      return sb.toString();
    }
  };

  @SkylarkSignature(name = "split", objectType = StringModule.class,
      returnType = MutableList.class,
      doc = "Returns a list of all the words in the string, using <code>sep</code>  "
          + "as the separator, optionally limiting the number of splits to <code>maxsplit</code>.",
      mandatoryPositionals = {
        @Param(name = "self", type = String.class, doc = "This string."),
        @Param(name = "sep", type = String.class, doc = "The string to split on.")},
      optionalPositionals = {
        @Param(name = "maxsplit", type = Integer.class, noneable = true, defaultValue = "None",
            doc = "The maximum number of splits.")},
      useEnvironment = true,
      useLocation = true)
  private static BuiltinFunction split = new BuiltinFunction("split") {
    public MutableList invoke(String self, String sep, Object maxSplitO, Location loc,
        Environment env) throws ConversionException, EvalException {
      int maxSplit = Type.INTEGER.convertOptional(
          maxSplitO, "'split' argument of 'split'", /*label*/null, -2);
      // + 1 because the last result is the remainder, and default of -2 so that after +1 it's -1
      String[] ss = Pattern.compile(sep, Pattern.LITERAL).split(self, maxSplit + 1);
      return MutableList.of(env, (Object[]) ss);
    }
  };

  @SkylarkSignature(name = "rsplit", objectType = StringModule.class,
      returnType = MutableList.class,
      doc = "Returns a list of all the words in the string, using <code>sep</code>  "
          + "as the separator, optionally limiting the number of splits to <code>maxsplit</code>. "
          + "Except for splitting from the right, this method behaves like split().",
      mandatoryPositionals = {
        @Param(name = "self", type = String.class, doc = "This string."),
        @Param(name = "sep", type = String.class, doc = "The string to split on.")},
      optionalPositionals = {
        @Param(name = "maxsplit", type = Integer.class, noneable = true,
          defaultValue = "None", doc = "The maximum number of splits.")},
      useEnvironment = true,
      useLocation = true)
  private static BuiltinFunction rsplit = new BuiltinFunction("rsplit") {
    @SuppressWarnings("unused")
    public MutableList invoke(
        String self, String sep, Object maxSplitO, Location loc, Environment env)
        throws ConversionException, EvalException {
      int maxSplit =
          Type.INTEGER.convertOptional(maxSplitO, "'split' argument of 'split'", null, -1);
      List<String> result;

      try {
        return stringRSplit(self, sep, maxSplit, env);
      } catch (IllegalArgumentException ex) {
        throw new EvalException(loc, ex);
      }
    }
  };

  /**
   * Splits the given string into a list of words, using {@code separator} as a
   * delimiter.
   *
   * <p>At most {@code maxSplits} will be performed, going from right to left.
   *
   * @param input The input string.
   * @param separator The separator string.
   * @param maxSplits The maximum number of splits. Negative values mean unlimited splits.
   * @return A list of words
   * @throws IllegalArgumentException
   */
  private static MutableList stringRSplit(
      String input, String separator, int maxSplits, Environment env)
      throws IllegalArgumentException {
    if (separator.isEmpty()) {
      throw new IllegalArgumentException("Empty separator");
    }

    if (maxSplits <= 0) {
      maxSplits = Integer.MAX_VALUE;
    }

    LinkedList<String> result = new LinkedList<>();
    String[] parts = input.split(Pattern.quote(separator), -1);
    int sepLen = separator.length();
    int remainingLength = input.length();
    int splitsSoFar = 0;

    // Copies parts from the array into the final list, starting at the end (because
    // it's rsplit), as long as fewer than maxSplits splits are performed. The
    // last spot in the list is reserved for the remaining string, whose length
    // has to be tracked throughout the loop.
    for (int pos = parts.length - 1; (pos >= 0) && (splitsSoFar < maxSplits); --pos) {
      String current = parts[pos];
      result.addFirst(current);

      ++splitsSoFar;
      remainingLength -= sepLen + current.length();
    }

    if (splitsSoFar == maxSplits && remainingLength >= 0)   {
      result.addFirst(input.substring(0, remainingLength));
    }

    return new MutableList(result, env);
  }

  @SkylarkSignature(name = "partition", objectType = StringModule.class,
      returnType = MutableList.class,
      doc = "Splits the input string at the first occurrence of the separator "
          + "<code>sep</code> and returns the resulting partition as a three-element "
          + "list of the form [substring_before, separator, substring_after].",
      mandatoryPositionals = {
        @Param(name = "self", type = String.class, doc = "This string.")},
      optionalPositionals = {
        @Param(name = "sep", type = String.class,
          defaultValue = "' '", doc = "The string to split on, default is space (\" \").")},
      useEnvironment = true,
      useLocation = true)
  private static BuiltinFunction partition = new BuiltinFunction("partition") {
    @SuppressWarnings("unused")
    public MutableList invoke(String self, String sep, Location loc, Environment env)
        throws EvalException {
      return partitionWrapper(self, sep, true, env, loc);
    }
  };

  @SkylarkSignature(name = "rpartition", objectType = StringModule.class,
      returnType = MutableList.class,
      doc = "Splits the input string at the last occurrence of the separator "
          + "<code>sep</code> and returns the resulting partition as a three-element "
          + "list of the form [substring_before, separator, substring_after].",
      mandatoryPositionals = {
        @Param(name = "self", type = String.class, doc = "This string.")},
      optionalPositionals = {
        @Param(name = "sep", type = String.class,
          defaultValue = "' '", doc = "The string to split on, default is space (\" \").")},
      useEnvironment = true,
      useLocation = true)
  private static BuiltinFunction rpartition = new BuiltinFunction("rpartition") {
    @SuppressWarnings("unused")
    public MutableList invoke(String self, String sep, Location loc, Environment env)
        throws EvalException {
      return partitionWrapper(self, sep, false, env, loc);
    }
  };

  /**
   * Wraps the stringPartition() method and converts its results and exceptions
   * to the expected types.
   *
   * @param self The input string
   * @param separator The string to split on
   * @param forward A flag that controls whether the input string is split around
   *    the first ({@code true}) or last ({@code false}) occurrence of the separator.
   * @param env The current environment
   * @param loc The location that is used for potential exceptions
   * @return A list with three elements
   */
  private static MutableList partitionWrapper(String self, String separator, boolean forward,
      Environment env, Location loc) throws EvalException {
    try {
      return new MutableList(stringPartition(self, separator, forward), env);
    } catch (IllegalArgumentException ex) {
      throw new EvalException(loc, ex);
    }
  }

  /**
   * Splits the input string at the {first|last} occurrence of the given separator
   * and returns the resulting partition as a three-tuple of Strings, contained
   * in a {@code MutableList}.
   *
   * <p>If the input string does not contain the separator, the tuple will
   * consist of the original input string and two empty strings.
   *
   * <p>This method emulates the behavior of Python's str.partition() and
   * str.rpartition(), depending on the value of the {@code forward} flag.
   *
   * @param input The input string
   * @param separator The string to split on
   * @param forward A flag that controls whether the input string is split around
   *    the first ({@code true}) or last ({@code false}) occurrence of the separator.
   * @return A three-tuple (List) of the form [part_before_separator, separator,
   *    part_after_separator].
   *
   */
  private static List<String> stringPartition(String input, String separator, boolean forward)
      throws IllegalArgumentException {
    if (separator.isEmpty()) {
      throw new IllegalArgumentException("Empty separator");
    }

    int partitionSize = 3;
    ArrayList<String> result = new ArrayList<>(partitionSize);
    int pos = forward ? input.indexOf(separator) : input.lastIndexOf(separator);

    if (pos < 0) {
      for (int i = 0; i < partitionSize; ++i) {
        result.add("");
      }

      // Following Python's implementation of str.partition() and str.rpartition(),
      // the input string is copied to either the first or the last position in the
      // list, depending on the value of the forward flag.
      result.set(forward ? 0 : partitionSize - 1, input);
    } else {
      result.add(input.substring(0, pos));
      result.add(separator);

      // pos + sep.length() is at most equal to input.length(). This worst-case
      // happens when the separator is at the end of the input string. However,
      // substring() will return an empty string in this scenario, thus making
      // any additional safety checks obsolete.
      result.add(input.substring(pos + separator.length()));
    }

    return result;
  }

  @SkylarkSignature(
    name = "capitalize",
    objectType = StringModule.class,
    returnType = String.class,
    doc =
        "Returns a copy of the string with its first character capitalized and the rest "
            + "lowercased. This method does not support non-ascii characters.",
    mandatoryPositionals = {@Param(name = "self", type = String.class, doc = "This string.")}
  )
  private static BuiltinFunction capitalize =
      new BuiltinFunction("capitalize") {
        @SuppressWarnings("unused")
        public String invoke(String self) throws EvalException {
          if (self.isEmpty()) {
            return self;
          }
          return Character.toUpperCase(self.charAt(0)) + self.substring(1).toLowerCase();
        }
      };

  @SkylarkSignature(name = "title", objectType = StringModule.class,
      returnType = String.class,
      doc =
      "Converts the input string into title case, i.e. every word starts with an "
      + "uppercase letter while the remaining letters are lowercase. In this "
      + "context, a word means strictly a sequence of letters. This method does "
      + "not support supplementary Unicode characters.",
      mandatoryPositionals = {
        @Param(name = "self", type = String.class, doc = "This string.")})
  private static BuiltinFunction title = new BuiltinFunction("title") {
    @SuppressWarnings("unused")
    public String invoke(String self) throws EvalException {
      char[] data = self.toCharArray();
      boolean previousWasLetter = false;

      for (int pos = 0; pos < data.length; ++pos) {
        char current = data[pos];
        boolean currentIsLetter = Character.isLetter(current);

        if (currentIsLetter) {
          if (previousWasLetter && Character.isUpperCase(current)) {
            data[pos] = Character.toLowerCase(current);
          } else if (!previousWasLetter && Character.isLowerCase(current)) {
            data[pos] = Character.toUpperCase(current);
          }
        }
        previousWasLetter = currentIsLetter;
      }

      return new String(data);
    }
  };

  /**
   * Common implementation for find, rfind, index, rindex.
   * @param forward true if we want to return the last matching index.
   */
  private static int stringFind(boolean forward,
      String self, String sub, int start, Object end, String msg)
      throws ConversionException {
    String substr = pythonSubstring(self, start, end, msg);
    int subpos = forward ? substr.indexOf(sub) : substr.lastIndexOf(sub);
    start = clampIndex(start, self.length());
    return subpos < 0 ? subpos : subpos + start;
  }

  @SkylarkSignature(name = "rfind", objectType = StringModule.class, returnType = Integer.class,
      doc = "Returns the last index where <code>sub</code> is found, "
          + "or -1 if no such index exists, optionally restricting to "
          + "[<code>start</code>:<code>end</code>], "
          + "<code>start</code> being inclusive and <code>end</code> being exclusive.",
      mandatoryPositionals = {
        @Param(name = "self", type = String.class, doc = "This string."),
        @Param(name = "sub", type = String.class, doc = "The substring to find.")},
      optionalPositionals = {
        @Param(name = "start", type = Integer.class, defaultValue = "0",
            doc = "Restrict to search from this position."),
        @Param(name = "end", type = Integer.class, noneable = true, defaultValue = "None",
            doc = "optional position before which to restrict to search.")})
  private static BuiltinFunction rfind = new BuiltinFunction("rfind") {
    public Integer invoke(String self, String sub, Integer start, Object end)
        throws ConversionException {
      return stringFind(false, self, sub, start, end, "'end' argument to rfind");
    }
  };

  @SkylarkSignature(name = "find", objectType = StringModule.class, returnType = Integer.class,
      doc = "Returns the first index where <code>sub</code> is found, "
          + "or -1 if no such index exists, optionally restricting to "
          + "[<code>start</code>:<code>end]</code>, "
          + "<code>start</code> being inclusive and <code>end</code> being exclusive.",
      mandatoryPositionals = {
        @Param(name = "self", type = String.class, doc = "This string."),
        @Param(name = "sub", type = String.class, doc = "The substring to find.")},
      optionalPositionals = {
        @Param(name = "start", type = Integer.class, defaultValue = "0",
            doc = "Restrict to search from this position."),
        @Param(name = "end", type = Integer.class, noneable = true, defaultValue = "None",
            doc = "optional position before which to restrict to search.")})
  private static BuiltinFunction find = new BuiltinFunction("find") {
    public Integer invoke(String self, String sub, Integer start, Object end)
        throws ConversionException {
      return stringFind(true, self, sub, start, end, "'end' argument to find");
    }
  };

  @SkylarkSignature(name = "rindex", objectType = StringModule.class, returnType = Integer.class,
      doc = "Returns the last index where <code>sub</code> is found, "
          + "or raises an error if no such index exists, optionally restricting to "
          + "[<code>start</code>:<code>end</code>], "
          + "<code>start</code> being inclusive and <code>end</code> being exclusive.",
      mandatoryPositionals = {
        @Param(name = "self", type = String.class, doc = "This string."),
        @Param(name = "sub", type = String.class, doc = "The substring to find.")},
      optionalPositionals = {
        @Param(name = "start", type = Integer.class, defaultValue = "0",
            doc = "Restrict to search from this position."),
        @Param(name = "end", type = Integer.class, noneable = true, defaultValue = "None",
            doc = "optional position before which to restrict to search.")},
      useLocation = true)
  private static BuiltinFunction rindex = new BuiltinFunction("rindex") {
    public Integer invoke(String self, String sub, Integer start, Object end,
        Location loc) throws EvalException, ConversionException {
      int res = stringFind(false, self, sub, start, end, "'end' argument to rindex");
      if (res < 0) {
        throw new EvalException(loc, Printer.format("substring %r not found in %r", sub, self));
      }
      return res;
    }
  };

  @SkylarkSignature(name = "index", objectType = StringModule.class, returnType = Integer.class,
      doc = "Returns the first index where <code>sub</code> is found, "
          + "or raises an error if no such index exists, optionally restricting to "
          + "[<code>start</code>:<code>end]</code>, "
          + "<code>start</code> being inclusive and <code>end</code> being exclusive.",
      mandatoryPositionals = {
        @Param(name = "self", type = String.class, doc = "This string."),
        @Param(name = "sub", type = String.class, doc = "The substring to find.")},
      optionalPositionals = {
        @Param(name = "start", type = Integer.class, defaultValue = "0",
            doc = "Restrict to search from this position."),
        @Param(name = "end", type = Integer.class, noneable = true,
            doc = "optional position before which to restrict to search.")},
      useLocation = true)
  private static BuiltinFunction index = new BuiltinFunction("index") {
    public Integer invoke(String self, String sub, Integer start, Object end,
        Location loc) throws EvalException, ConversionException {
      int res = stringFind(true, self, sub, start, end, "'end' argument to index");
      if (res < 0) {
        throw new EvalException(loc, Printer.format("substring %r not found in %r", sub, self));
      }
      return res;
    }
  };

  @SkylarkSignature(name = "isalpha", objectType = StringModule.class, returnType = Boolean.class,
    doc = "Returns True if all characters in the string are alphabetic ([a-zA-Z]) and it "
        + "contains at least one character.",
    mandatoryPositionals = {
        @Param(name = "self", type = String.class, doc = "This string.")})
  private static BuiltinFunction isalpha = new BuiltinFunction("isalpha") {
    public Boolean invoke(String self) throws EvalException {
      int length = self.length();
      if (length < 1) {
        return false;
      }
      for (int index = 0; index < length; index++) {
        char character = self.charAt(index);
        if (!((character >= 'A' && character <= 'Z')
            || (character >= 'a' && character <= 'z'))) {
          return false;
        }
      }
      return true;
    }
  };

  @SkylarkSignature(name = "count", objectType = StringModule.class, returnType = Integer.class,
      doc = "Returns the number of (non-overlapping) occurrences of substring <code>sub</code> in "
          + "string, optionally restricting to [<code>start</code>:<code>end</code>], "
          + "<code>start</code> being inclusive and <code>end</code> being exclusive.",
      mandatoryPositionals = {
        @Param(name = "self", type = String.class, doc = "This string."),
        @Param(name = "sub", type = String.class, doc = "The substring to count.")},
      optionalPositionals = {
        @Param(name = "start", type = Integer.class, defaultValue = "0",
            doc = "Restrict to search from this position."),
        @Param(name = "end", type = Integer.class, noneable = true, defaultValue = "None",
            doc = "optional position before which to restrict to search.")})
  private static BuiltinFunction count = new BuiltinFunction("count") {
    public Integer invoke(String self, String sub, Integer start, Object end)
        throws ConversionException {
      String str = pythonSubstring(self, start, end, "'end' operand of 'find'");
      if (sub.isEmpty()) {
        return str.length() + 1;
      }
      int count = 0;
      int index = -1;
      while ((index = str.indexOf(sub)) >= 0) {
        count++;
        str = str.substring(index + sub.length());
      }
      return count;
    }
  };

  @SkylarkSignature(name = "endswith", objectType = StringModule.class, returnType = Boolean.class,
      doc = "Returns True if the string ends with <code>sub</code>, "
          + "otherwise False, optionally restricting to [<code>start</code>:<code>end</code>], "
          + "<code>start</code> being inclusive and <code>end</code> being exclusive.",
      mandatoryPositionals = {
        @Param(name = "self", type = String.class, doc = "This string."),
        @Param(name = "sub", type = String.class, doc = "The substring to check.")},
      optionalPositionals = {
        @Param(name = "start", type = Integer.class, defaultValue = "0",
            doc = "Test beginning at this position."),
        @Param(name = "end", type = Integer.class, noneable = true, defaultValue = "None",
            doc = "optional position at which to stop comparing.")})
  private static BuiltinFunction endswith = new BuiltinFunction("endswith") {
    public Boolean invoke(String self, String sub, Integer start, Object end)
        throws ConversionException {
      return pythonSubstring(self, start, end, "'end' operand of 'endswith'").endsWith(sub);
    }
  };

  // In Python, formatting is very complex.
  // We handle here the simplest case which provides most of the value of the function.
  // https://docs.python.org/3/library/string.html#formatstrings
  @SkylarkSignature(name = "format", objectType = StringModule.class, returnType = String.class,
      doc = "Perform string interpolation. Format strings contain replacement fields "
          + "surrounded by curly braces <code>{}</code>. Anything that is not contained "
          + "in braces is considered literal text, which is copied unchanged to the output."
          + "If you need to include a brace character in the literal text, it can be "
          + "escaped by doubling: <code>{{</code> and <code>}}</code>"
          + "A replacement field can be either a name, a number, or empty. Values are "
          + "converted to strings using the <a href=\"globals.html#str\">str</a> function."
          + "<pre class=\"language-python\">"
          + "# Access in order:\n"
          + "\"{} < {}\".format(4, 5) == \"4 < 5\"\n"
          + "# Access by position:\n"
          + "\"{1}, {0}\".format(2, 1) == \"1, 2\"\n"
          + "# Access by name:\n"
          + "\"x{key}x\".format(key = 2) == \"x2x\"</pre>\n",
      mandatoryPositionals = {
          @Param(name = "self", type = String.class, doc = "This string."),
      },
      extraPositionals = {
          @Param(name = "args", type = SkylarkList.class, defaultValue = "()",
              doc = "List of arguments"),
      },
      extraKeywords = {@Param(name = "kwargs", doc = "Dictionary of arguments")},
      useLocation = true)
  private static BuiltinFunction format = new BuiltinFunction("format") {
    @SuppressWarnings("unused")
    public String invoke(String self, SkylarkList args, Map<String, Object> kwargs, Location loc)
        throws ConversionException, EvalException {
      return new FormatParser(loc).format(self, args.getList(), kwargs);
    }
  };

  @SkylarkSignature(name = "startswith", objectType = StringModule.class,
      returnType = Boolean.class,
      doc = "Returns True if the string starts with <code>sub</code>, "
          + "otherwise False, optionally restricting to [<code>start</code>:<code>end</code>], "
          + "<code>start</code> being inclusive and <code>end</code> being exclusive.",
      mandatoryPositionals = {
        @Param(name = "self", type = String.class, doc = "This string."),
        @Param(name = "sub", type = String.class, doc = "The substring to check.")},
      optionalPositionals = {
        @Param(name = "start", type = Integer.class, defaultValue = "0",
            doc = "Test beginning at this position."),
        @Param(name = "end", type = Integer.class, noneable = true, defaultValue = "None",
            doc = "Stop comparing at this position.")})
  private static BuiltinFunction startswith = new BuiltinFunction("startswith") {
    public Boolean invoke(String self, String sub, Integer start, Object end)
        throws ConversionException {
      return pythonSubstring(self, start, end, "'end' operand of 'startswith'").startsWith(sub);
    }
  };

  // slice operator
  @SkylarkSignature(name = "$slice", objectType = String.class,
      documented = false,
      mandatoryPositionals = {
        @Param(name = "self", type = String.class, doc = "This string."),
        @Param(name = "start", type = Integer.class, doc = "start position of the slice."),
        @Param(name = "end", type = Integer.class, doc = "end position of the slice.")},
      doc = "x[<code>start</code>:<code>end</code>] returns a slice or a list slice.")
  private static BuiltinFunction stringSlice = new BuiltinFunction("$slice") {
    public Object invoke(String self, Integer left, Integer right)
        throws EvalException, ConversionException {
      return pythonSubstring(self, left, right, "");
    }
  };

  @SkylarkSignature(name = "$slice", objectType = MutableList.class, returnType = MutableList.class,
      documented = false,
      mandatoryPositionals = {
        @Param(name = "self", type = MutableList.class, doc = "This list."),
        @Param(name = "start", type = Integer.class, doc = "start position of the slice."),
        @Param(name = "end", type = Integer.class, doc = "end position of the slice.")},
      doc = "x[<code>start</code>:<code>end</code>] returns a slice or a list slice.",
      useEnvironment = true)
  private static BuiltinFunction mutableListSlice = new BuiltinFunction("$slice") {
    public MutableList invoke(MutableList self, Integer left, Integer right,
        Environment env) throws EvalException, ConversionException {
      return new MutableList(sliceList(self.getList(), left, right), env);
    }
  };

  @SkylarkSignature(name = "$slice", objectType = Tuple.class, returnType = Tuple.class,
      documented = false,
      mandatoryPositionals = {
        @Param(name = "self", type = Tuple.class, doc = "This tuple."),
        @Param(name = "start", type = Integer.class, doc = "start position of the slice."),
        @Param(name = "end", type = Integer.class, doc = "end position of the slice.")},
      doc = "x[<code>start</code>:<code>end</code>] returns a slice or a list slice.",
      useEnvironment = true)
  private static BuiltinFunction tupleSlice = new BuiltinFunction("$slice") {
      public Tuple invoke(Tuple self, Integer left, Integer right,
          Environment env) throws EvalException, ConversionException {
        return Tuple.copyOf(sliceList(self.getList(), left, right));
      }
    };

  private static List<Object> sliceList(List<Object> list, Integer left, Integer right) {
    left = clampIndex(left, list.size());
    right = clampIndex(right, list.size());
    if (left > right) {
      left = right;
    }
    return list.subList(left, right);
  }

  // supported list methods
  @SkylarkSignature(
    name = "sorted",
    returnType = MutableList.class,
    doc =
        "Sort a collection. Elements are sorted first by their type, "
            + "then by their value (in ascending order).",
    mandatoryPositionals = {@Param(name = "self", type = Object.class, doc = "This collection.")},
    useLocation = true,
    useEnvironment = true
  )
  private static BuiltinFunction sorted =
      new BuiltinFunction("sorted") {
        public MutableList invoke(Object self, Location loc, Environment env)
            throws EvalException, ConversionException {
          try {
            return new MutableList(
                Ordering.from(EvalUtils.SKYLARK_COMPARATOR).sortedCopy(
                    EvalUtils.toCollection(self, loc)),
                env);
          } catch (EvalUtils.ComparisonException e) {
            throw new EvalException(loc, e);
          }
        }
      };

  @SkylarkSignature(
    name = "append",
    objectType = MutableList.class,
    returnType = Runtime.NoneType.class,
    documented = false,
    doc = "Adds an item to the end of the list.",
    mandatoryPositionals = {
      @Param(name = "self", type = MutableList.class, doc = "This list."),
      @Param(name = "item", type = Object.class, doc = "Item to add at the end.")
    },
    useLocation = true,
    useEnvironment = true)
  private static BuiltinFunction append =
      new BuiltinFunction("append") {
        public Runtime.NoneType invoke(MutableList self, Object item,
            Location loc, Environment env) throws EvalException, ConversionException {
          self.add(item, loc, env);
          return Runtime.NONE;
        }
      };

  @SkylarkSignature(
    name = "extend",
    objectType = MutableList.class,
    returnType = Runtime.NoneType.class,
    documented = false,
    doc = "Adds all items to the end of the list.",
    mandatoryPositionals = {
      @Param(name = "self", type = MutableList.class, doc = "This list."),
      @Param(name = "items", type = SkylarkList.class, doc = "Items to add at the end.")},
    useLocation = true,
    useEnvironment = true)
  private static BuiltinFunction extend =
      new BuiltinFunction("extend") {
        public Runtime.NoneType invoke(MutableList self, SkylarkList items,
            Location loc, Environment env) throws EvalException, ConversionException {
          self.addAll(items, loc, env);
          return Runtime.NONE;
        }
      };

  @SkylarkSignature(
    name = "index",
    objectType = MutableList.class,
    returnType = Integer.class,
    doc =
        "Returns the index in the list of the first item whose value is x. "
            + "It is an error if there is no such item.",
    mandatoryPositionals = {
      @Param(name = "self", type = MutableList.class, doc = "This string, a separator."),
      @Param(name = "x", type = Object.class, doc = "The object to search.")
    },
    useLocation = true
  )
  private static BuiltinFunction listIndex =
      new BuiltinFunction("index") {
        public Integer invoke(MutableList self, Object x, Location loc) throws EvalException {
          int i = 0;
          for (Object obj : self) {
            if (obj.equals(x)) {
              return i;
            }
            i++;
          }
          throw new EvalException(loc, Printer.format("Item %r not found in list", x));
        }
      };

  // dictionary access operator
  @SkylarkSignature(name = "$index", documented = false, objectType = Map.class,
      doc = "Looks up a value in a dictionary.",
      mandatoryPositionals = {
        @Param(name = "self", type = Map.class, doc = "This object."),
        @Param(name = "key", type = Object.class, doc = "The index or key to access.")},
      useLocation = true, useEnvironment = true)
  private static BuiltinFunction dictIndexOperator = new BuiltinFunction("$index") {
    public Object invoke(Map<?, ?> self, Object key,
        Location loc, Environment env) throws EvalException, ConversionException {
      if (!self.containsKey(key)) {
        throw new EvalException(loc, Printer.format("Key %r not found in dictionary", key));
      }
      return SkylarkType.convertToSkylark(self.get(key), env);
    }
  };

  // list access operator
  @SkylarkSignature(name = "$index", documented = false, objectType = MutableList.class,
      doc = "Returns the nth element of a list.",
      mandatoryPositionals = {
        @Param(name = "self", type = MutableList.class, doc = "This list."),
        @Param(name = "key", type = Object.class, doc = "The index or key to access.")},
      useLocation = true, useEnvironment = true)
  private static BuiltinFunction listIndexOperator = new BuiltinFunction("$index") {
      public Object invoke(MutableList self, Object key,
          Location loc, Environment env) throws EvalException, ConversionException {
        if (self.isEmpty()) {
          throw new EvalException(loc, "List is empty");
        }
        int index = getListIndex(key, self.size(), loc);
        return SkylarkType.convertToSkylark(self.getList().get(index), env);
      }
    };

  // tuple access operator
  @SkylarkSignature(name = "$index", documented = false, objectType = Tuple.class,
      doc = "Returns the nth element of a tuple.",
      mandatoryPositionals = {
        @Param(name = "self", type = Tuple.class, doc = "This tuple."),
        @Param(name = "key", type = Object.class, doc = "The index or key to access.")},
      useLocation = true, useEnvironment = true)
  private static BuiltinFunction tupleIndexOperator = new BuiltinFunction("$index") {
      public Object invoke(Tuple self, Object key,
          Location loc, Environment env) throws EvalException, ConversionException {
        if (self.isEmpty()) {
          throw new EvalException(loc, "tuple is empty");
        }
        int index = getListIndex(key, self.size(), loc);
        return SkylarkType.convertToSkylark(self.getList().get(index), env);
      }
    };

  @SkylarkSignature(name = "$index", documented = false, objectType = String.class,
      doc = "Returns the nth element of a string.",
      mandatoryPositionals = {
        @Param(name = "self", type = String.class, doc = "This string."),
        @Param(name = "key", type = Object.class, doc = "The index or key to access.")},
      useLocation = true)
  private static BuiltinFunction stringIndexOperator = new BuiltinFunction("$index") {
    public Object invoke(String self, Object key,
        Location loc) throws EvalException, ConversionException {
      int index = getListIndex(key, self.length(), loc);
      return self.substring(index, index + 1);
    }
  };

  @SkylarkSignature(name = "values", objectType = Map.class,
      returnType = MutableList.class,
      doc = "Returns the list of values. Dictionaries are always sorted by their keys:"
          + "<pre class=\"language-python\">"
          + "{2: \"a\", 4: \"b\", 1: \"c\"}.values() == [\"c\", \"a\", \"b\"]</pre>\n",
      mandatoryPositionals = {@Param(name = "self", type = Map.class, doc = "This dict.")},
      useEnvironment = true)
  private static BuiltinFunction values = new BuiltinFunction("values") {
    public MutableList invoke(Map<?, ?> self,
        Environment env) throws EvalException, ConversionException {
      // Use a TreeMap to ensure consistent ordering.
      Map<?, ?> dict = new TreeMap<>(self);
      return new MutableList(dict.values(), env);
    }
  };

  @SkylarkSignature(name = "items", objectType = Map.class,
      returnType = MutableList.class,
      doc = "Returns the list of key-value tuples. Dictionaries are always sorted by their keys:"
          + "<pre class=\"language-python\">"
          + "{2: \"a\", 4: \"b\", 1: \"c\"}.items() == [(1, \"c\"), (2, \"a\"), (4, \"b\")]"
          + "</pre>\n",
      mandatoryPositionals = {
        @Param(name = "self", type = Map.class, doc = "This dict.")},
      useEnvironment = true)
  private static BuiltinFunction items = new BuiltinFunction("items") {
    public MutableList invoke(Map<?, ?> self,
        Environment env) throws EvalException, ConversionException {
      // Use a TreeMap to ensure consistent ordering.
      Map<?, ?> dict = new TreeMap<>(self);
      List<Object> list = Lists.newArrayListWithCapacity(dict.size());
      for (Map.Entry<?, ?> entries : dict.entrySet()) {
        list.add(Tuple.of(entries.getKey(), entries.getValue()));
      }
      return new MutableList(list, env);
    }
  };

  @SkylarkSignature(name = "keys", objectType = Map.class,
      returnType = MutableList.class,
      doc = "Returns the list of keys. Dictionaries are always sorted by their keys:"
          + "<pre class=\"language-python\">{2: \"a\", 4: \"b\", 1: \"c\"}.keys() == [1, 2, 4]"
          + "</pre>\n",
      mandatoryPositionals = {
        @Param(name = "self", type = Map.class, doc = "This dict.")},
      useEnvironment = true)
  // Skylark will only call this on a dict; and
  // allowed keys are all Comparable... if not mutually, it's OK to get a runtime exception.
  private static BuiltinFunction keys = new BuiltinFunction("keys") {
    public MutableList invoke(Map<Comparable<?>, ?> dict,
        Environment env) throws EvalException {
      return new MutableList(Ordering.natural().sortedCopy(dict.keySet()), env);
    }
  };

  @SkylarkSignature(name = "get", objectType = Map.class,
      doc = "Returns the value for <code>key</code> if <code>key</code> is in the dictionary, "
          + "else <code>default</code>. If <code>default</code> is not given, it defaults to "
          + "<code>None</code>, so that this method never throws an error.",
      mandatoryPositionals = {
        @Param(name = "self", doc = "This dict."),
        @Param(name = "key", doc = "The key to look for.")},
      optionalPositionals = {
        @Param(name = "default", defaultValue = "None",
            doc = "The default value to use (instead of None) if the key is not found.")})
  private static BuiltinFunction get = new BuiltinFunction("get") {
    public Object invoke(Map<?, ?> self, Object key, Object defaultValue) {
      if (self.containsKey(key)) {
        return self.get(key);
      }
      return defaultValue;
    }
  };

  // unary minus
  @SkylarkSignature(name = "-", returnType = Integer.class,
      documented = false,
      doc = "Unary minus operator.",
      mandatoryPositionals = {
        @Param(name = "num", type = Integer.class, doc = "The number to negate.")})
  private static BuiltinFunction minus = new BuiltinFunction("-") {
    public Integer invoke(Integer num) throws ConversionException {
      return -num;
    }
  };

  @SkylarkSignature(name = "list", returnType = MutableList.class,
      doc = "Converts a collection (e.g. set or dictionary) to a list."
        + "<pre class=\"language-python\">list([1, 2]) == [1, 2]\n"
        + "list(set([2, 3, 2])) == [2, 3]\n"
        + "list({5: \"a\", 2: \"b\", 4: \"c\"}) == [2, 4, 5]</pre>",
      mandatoryPositionals = {@Param(name = "x", doc = "The object to convert.")},
      useLocation = true, useEnvironment = true)
  private static BuiltinFunction list = new BuiltinFunction("list") {
    public MutableList invoke(Object x, Location loc, Environment env) throws EvalException {
      return new MutableList(EvalUtils.toCollection(x, loc), env);
    }
  };

  @SkylarkSignature(name = "len", returnType = Integer.class, doc =
      "Returns the length of a string, list, tuple, set, or dictionary.",
      mandatoryPositionals = {@Param(name = "x", doc = "The object to check length of.")},
      useLocation = true)
  private static BuiltinFunction len = new BuiltinFunction("len") {
    public Integer invoke(Object x, Location loc) throws EvalException {
      int l = EvalUtils.size(x);
      if (l == -1) {
        throw new EvalException(loc, EvalUtils.getDataTypeName(x) + " is not iterable");
      }
      return l;
    }
  };

  @SkylarkSignature(name = "str", returnType = String.class, doc =
      "Converts any object to string. This is useful for debugging."
      + "<pre class=\"language-python\">str(\"ab\") == \"ab\"</pre>",
      mandatoryPositionals = {@Param(name = "x", doc = "The object to convert.")})
  private static BuiltinFunction str = new BuiltinFunction("str") {
    public String invoke(Object x) {
      return Printer.str(x);
    }
  };

  @SkylarkSignature(name = "repr", returnType = String.class, doc =
      "Converts any object to a string representation. This is useful for debugging.<br>"
      + "<pre class=\"language-python\">str(\"ab\") == \\\"ab\\\"</pre>",
      mandatoryPositionals = {@Param(name = "x", doc = "The object to convert.")})
  private static BuiltinFunction repr = new BuiltinFunction("repr") {
    public String invoke(Object x) {
      return Printer.repr(x);
    }
  };

  @SkylarkSignature(name = "bool", returnType = Boolean.class,
      doc = "Constructor for the bool type. "
      + "It returns False if the object is None, False, an empty string, the number 0, or an "
      + "empty collection. Otherwise, it returns True.",
      mandatoryPositionals = {@Param(name = "x", doc = "The variable to convert.")})
  private static BuiltinFunction bool = new BuiltinFunction("bool") {
    public Boolean invoke(Object x) throws EvalException {
      return EvalUtils.toBoolean(x);
    }
  };

  @SkylarkSignature(name = "int", returnType = Integer.class, doc = "Converts a value to int. "
      + "If the argument is a string, it is converted using base 10 and raises an error if the "
      + "conversion fails. If the argument is a bool, it returns 0 (False) or 1 (True). "
      + "If the argument is an int, it is simply returned."
      + "<pre class=\"language-python\">int(\"123\") == 123</pre>",
      mandatoryPositionals = {
        @Param(name = "x", type = Object.class, doc = "The string to convert.")},
      useLocation = true)
  private static BuiltinFunction int_ = new BuiltinFunction("int") {
    public Integer invoke(Object x, Location loc) throws EvalException {
      if (x instanceof Boolean) {
        return ((Boolean) x).booleanValue() ? 1 : 0;
      } else if (x instanceof Integer) {
        return (Integer) x;
      } else if (x instanceof String) {
        try {
          return Integer.parseInt((String) x);
        } catch (NumberFormatException e) {
          throw new EvalException(loc,
              "invalid literal for int(): " + Printer.repr(x));
        }
      } else {
        throw new EvalException(loc,
            Printer.format("%r is not of type string or int or bool", x));
      }
    }
  };

  @SkylarkSignature(name = "struct", returnType = SkylarkClassObject.class, doc =
      "Creates an immutable struct using the keyword arguments as attributes. It is used to group "
      + "multiple values together.Example:<br>"
      + "<pre class=\"language-python\">s = struct(x = 2, y = 3)\n"
      + "return s.x + getattr(s, \"y\")  # returns 5</pre>",
      extraKeywords = {
        @Param(name = "kwargs", doc = "the struct attributes")},
      useLocation = true)
  private static BuiltinFunction struct = new BuiltinFunction("struct") {
      @SuppressWarnings("unchecked")
    public SkylarkClassObject invoke(Map<String, Object> kwargs, Location loc)
        throws EvalException {
      return new SkylarkClassObject(kwargs, loc);
    }
  };

  @SkylarkSignature(
      name = "set",
      returnType = SkylarkNestedSet.class,
      doc = "Creates a <a href=\"set.html\">set</a> from the <code>items</code>. "
      + "The set supports nesting other sets of the same element type in it. "
      + "A desired <a href=\"set.html\">iteration order</a> can also be specified.<br>"
      + "Examples:<br><pre class=\"language-python\">set([\"a\", \"b\"])\n"
      + "set([1, 2, 3], order=\"compile\")</pre>",
      optionalPositionals = {
        @Param(name = "items", type = Object.class, defaultValue = "[]",
            doc = "The items to initialize the set with. May contain both standalone items "
            + "and other sets."),
        @Param(name = "order", type = String.class, defaultValue = "\"stable\"",
            doc = "The ordering strategy for the set if it's nested, "
            + "possible values are: <code>stable</code> (default), <code>compile</code>, "
            + "<code>link</code> or <code>naive_link</code>. An explanation of the "
            + "values can be found <a href=\"set.html\">here</a>.")},
      useLocation = true)
  private static final BuiltinFunction set = new BuiltinFunction("set") {
    public SkylarkNestedSet invoke(Object items, String order,
        Location loc) throws EvalException, ConversionException {
      try {
        return new SkylarkNestedSet(Order.parse(order), items, loc);
      } catch (IllegalArgumentException ex) {
        throw new EvalException(loc, ex);
      }
    }
  };

  @SkylarkSignature(name = "dict", returnType = Map.class,
      doc =
      "Creates a <a href=\"#modules.dict\">dictionary</a> from an optional positional "
      + "argument and an optional set of keyword arguments. Values from the keyword argument "
      + "will overwrite values from the positional argument if a key appears multiple times. "
      + "Dictionaries are always sorted by their keys",
      optionalPositionals = {
          @Param(name = "args", type = Object.class, defaultValue = "[]",
              doc =
              "Either a dictionary or a list of entries. Entries must be tuples or lists with "
              + "exactly two elements: key, value"),
      },
      extraKeywords = {@Param(name = "kwargs", doc = "Dictionary of additional entries.")},
      useLocation = true)
  private static final BuiltinFunction dict = new BuiltinFunction("dict") {
    @SuppressWarnings("unused")
    public Map<Object, Object> invoke(Object args, Map<Object, Object> kwargs, Location loc)
        throws EvalException {
      Map<Object, Object> result =
          (args instanceof Map<?, ?>)
              ? new LinkedHashMap<>((Map<?, ?>) args) : getMapFromArgs(args, loc);
      result.putAll(kwargs);
      return result;
    }

    private Map<Object, Object> getMapFromArgs(Object args, Location loc) throws EvalException {
      Map<Object, Object> result = new LinkedHashMap<>();
      int pos = 0;
      for (Object element : Type.OBJECT_LIST.convert(args, "parameter args in dict()")) {
        List<Object> pair = convertToPair(element, pos, loc);
        result.put(pair.get(0), pair.get(1));
        ++pos;
      }
      return result;
    }

    private List<Object> convertToPair(Object element, int pos, Location loc)
        throws EvalException {
      try {
        List<Object> tuple = Type.OBJECT_LIST.convert(element, "");
        int numElements = tuple.size();
        if (numElements != 2) {
          throw new EvalException(
              location,
              String.format("Sequence #%d has length %d, but exactly two elements are required",
                  pos, numElements));
        }
        return tuple;
      } catch (ConversionException e) {
        throw new EvalException(
            loc,
            String.format(
                "Cannot convert dictionary update sequence element #%d to a sequence", pos));
      }
    }
  };

  @SkylarkSignature(name = "union", objectType = SkylarkNestedSet.class,
      returnType = SkylarkNestedSet.class,
      doc = "Creates a new <a href=\"set.html\">set</a> that contains both "
          + "the input set as well as all additional elements.",
      mandatoryPositionals = {
        @Param(name = "input", type = SkylarkNestedSet.class, doc = "The input set"),
        @Param(name = "newElements", type = Iterable.class, doc = "The elements to be added")},
      useLocation = true)
  private static final BuiltinFunction union = new BuiltinFunction("union") {
    @SuppressWarnings("unused")
    public SkylarkNestedSet invoke(SkylarkNestedSet input, Iterable<Object> newElements,
        Location loc) throws EvalException {
      return new SkylarkNestedSet(input, newElements, loc);
    }
  };

  @SkylarkSignature(name = "enumerate", returnType = MutableList.class,
      doc = "Returns a list of pairs (two-element tuples), with the index (int) and the item from"
          + " the input list.\n<pre class=\"language-python\">"
          + "enumerate([24, 21, 84]) == [(0, 24), (1, 21), (2, 84)]</pre>\n",
      mandatoryPositionals = {
        @Param(name = "list", type = SkylarkList.class, doc = "input list")
      },
      useEnvironment = true)
  private static BuiltinFunction enumerate = new BuiltinFunction("enumerate") {
    public MutableList invoke(SkylarkList input, Environment env)
        throws EvalException, ConversionException {
      int count = 0;
      List<SkylarkList> result = Lists.newArrayList();
      for (Object obj : input) {
        result.add(Tuple.of(count, obj));
        count++;
      }
      return new MutableList(result, env);
    }
  };

  @SkylarkSignature(name = "range", returnType = MutableList.class,
      doc = "Creates a list where items go from <code>start</code> to <code>stop</code>, using a "
          + "<code>step</code> increment. If a single argument is provided, items will "
          + "range from 0 to that element."
          + "<pre class=\"language-python\">range(4) == [0, 1, 2, 3]\n"
          + "range(3, 9, 2) == [3, 5, 7]\n"
          + "range(3, 0, -1) == [3, 2, 1]</pre>",
      mandatoryPositionals = {
        @Param(name = "start_or_stop", type = Integer.class,
            doc = "Value of the start element if stop is provided, "
            + "otherwise value of stop and the actual start is 0"),
      },
      optionalPositionals = {
        @Param(name = "stop_or_none", type = Integer.class, noneable = true, defaultValue = "None",
            doc = "optional index of the first item <i>not</i> to be included in the "
            + "resulting list; generation of the list stops before <code>stop</code> is reached."),
        @Param(name = "step", type = Integer.class, defaultValue = "1",
            doc = "The increment (default is 1). It may be negative.")},
      useLocation = true,
      useEnvironment = true)
  private static final BuiltinFunction range = new BuiltinFunction("range") {
      public MutableList invoke(Integer startOrStop, Object stopOrNone, Integer step,
          Location loc, Environment env)
        throws EvalException, ConversionException {
      int start;
      int stop;
      if (stopOrNone == Runtime.NONE) {
        start = 0;
        stop = startOrStop;
      } else {
        start = startOrStop;
        stop = Type.INTEGER.convert(stopOrNone, "'stop' operand of 'range'");
      }
      if (step == 0) {
        throw new EvalException(loc, "step cannot be 0");
      }
      List<Integer> result = Lists.newArrayList();
      if (step > 0) {
        while (start < stop) {
          result.add(start);
          start += step;
        }
      } else {
        while (start > stop) {
          result.add(start);
          start += step;
        }
      }
      return new MutableList(result, env);
    }
  };

  /**
   * Returns a function-value implementing "select" (i.e. configurable attributes)
   * in the specified package context.
   */
  @SkylarkSignature(name = "select",
      doc = "Creates a SelectorValue from the dict parameter.",
      mandatoryPositionals = {
        @Param(name = "x", type = Map.class, doc = "The parameter to convert.")})
  private static final BuiltinFunction select = new BuiltinFunction("select") {
    public Object invoke(Map<?, ?> dict) throws EvalException {
      return SelectorList
          .of(new SelectorValue(dict));
    }
  };

  /**
   * Returns true if the object has a field of the given name, otherwise false.
   */
  @SkylarkSignature(name = "hasattr", returnType = Boolean.class,
      doc = "Returns True if the object <code>x</code> has an attribute of the given "
          + "<code>name</code>, otherwise False. Example:<br>"
          + "<pre class=\"language-python\">hasattr(ctx.attr, \"myattr\")</pre>",
      mandatoryPositionals = {
        @Param(name = "x", doc = "The object to check."),
        @Param(name = "name", type = String.class, doc = "The name of the attribute.")},
      useLocation = true, useEnvironment = true)
  private static final BuiltinFunction hasattr = new BuiltinFunction("hasattr") {
    public Boolean invoke(Object obj, String name,
        Location loc, Environment env) throws EvalException, ConversionException {
      if (obj instanceof ClassObject && ((ClassObject) obj).getValue(name) != null) {
        return true;
      }
      if (Runtime.getFunctionNames(obj.getClass()).contains(name)) {
        return true;
      }

      try {
        return FuncallExpression.getMethodNames(obj.getClass()).contains(name);
      } catch (ExecutionException e) {
        // This shouldn't happen
        throw new EvalException(loc, e.getMessage());
      }
    }
  };

  @SkylarkSignature(name = "getattr",
      doc = "Returns the struct's field of the given name if it exists. If not, it either returns "
          + "<code>default</code> (if specified) or raises an error. <code>getattr(x, \"foobar\")"
          + "</code> is equivalent to <code>x.foobar</code>."
          + "<pre class=\"language-python\">getattr(ctx.attr, \"myattr\")\n"
          + "getattr(ctx.attr, \"myattr\", \"mydefault\")</pre>",
      mandatoryPositionals = {
        @Param(name = "x", doc = "The struct whose attribute is accessed."),
        @Param(name = "name", doc = "The name of the struct attribute.")},
      optionalPositionals = {
        @Param(name = "default", defaultValue = "None",
            doc = "The default value to return in case the struct "
            + "doesn't have an attribute of the given name.")},
      useLocation = true, useEnvironment = true)
  private static final BuiltinFunction getattr = new BuiltinFunction("getattr") {
    public Object invoke(Object obj, String name, Object defaultValue,
        Location loc, Environment env) throws EvalException, ConversionException {
      Object result = DotExpression.eval(obj, name, loc, env);
      if (result == null) {
        if (defaultValue != Runtime.NONE) {
          return defaultValue;
        } else {
          throw new EvalException(loc, Printer.format("Object of type '%s' has no attribute %r",
                  EvalUtils.getDataTypeName(obj), name));
        }
      }
      return result;
    }
  };

  @SkylarkSignature(name = "dir", returnType = MutableList.class,
      doc = "Returns a list strings: the names of the attributes and "
          + "methods of the parameter object.",
      mandatoryPositionals = {@Param(name = "x", doc = "The object to check.")},
      useLocation = true, useEnvironment = true)
  private static final BuiltinFunction dir = new BuiltinFunction("dir") {
    public MutableList invoke(Object object,
        Location loc, Environment env) throws EvalException, ConversionException {
      // Order the fields alphabetically.
      Set<String> fields = new TreeSet<>();
      if (object instanceof ClassObject) {
        fields.addAll(((ClassObject) object).getKeys());
      }
      fields.addAll(Runtime.getFunctionNames(object.getClass()));
      try {
        fields.addAll(FuncallExpression.getMethodNames(object.getClass()));
      } catch (ExecutionException e) {
        // This shouldn't happen
        throw new EvalException(loc, e.getMessage());
      }
      return new MutableList(fields, env);
    }
  };

  @SkylarkSignature(name = "type", returnType = String.class,
      doc = "Returns the type name of its argument.",
      mandatoryPositionals = {@Param(name = "x", doc = "The object to check type of.")})
  private static final BuiltinFunction type = new BuiltinFunction("type") {
    public String invoke(Object object) {
      // There is no 'type' type in Skylark, so we return a string with the type name.
      return EvalUtils.getDataTypeName(object, false);
    }
  };

  @SkylarkSignature(name = "fail",
      doc = "Raises an error that cannot be intercepted. It can be used anywhere, "
          + "both in the loading phase and in the analysis phase.",
      returnType = Runtime.NoneType.class,
      mandatoryPositionals = {
        @Param(name = "msg", type = String.class, doc = "Error message to display for the user")},
      optionalPositionals = {
        @Param(name = "attr", type = String.class, noneable = true,
            defaultValue = "None",
            doc = "The name of the attribute that caused the error. This is used only for "
               + "error reporting.")},
      useLocation = true)
  private static final BuiltinFunction fail = new BuiltinFunction("fail") {
    public Runtime.NoneType invoke(String msg, Object attr,
        Location loc) throws EvalException, ConversionException {
      if (attr != Runtime.NONE) {
        msg = String.format("attribute %s: %s", attr, msg);
      }
      throw new EvalException(loc, msg);
    }
  };

  @SkylarkSignature(name = "print", returnType = Runtime.NoneType.class,
      doc = "Prints a warning with the text <code>msg</code>. It can be used for debugging or "
          + "for transition (before changing to an error). In other cases, warnings are "
          + "discouraged.",
      optionalNamedOnly = {
        @Param(name = "sep", type = String.class, defaultValue = "' '",
            doc = "The separator string between the objects, default is space (\" \").")},
      // NB: as compared to Python3, we're missing optional named-only arguments 'end' and 'file'
      extraPositionals = {@Param(name = "args", doc = "The objects to print.")},
      useLocation = true, useEnvironment = true)
  private static final BuiltinFunction print = new BuiltinFunction("print") {
    public Runtime.NoneType invoke(String sep, SkylarkList starargs,
        Location loc, Environment env) throws EvalException {
      String msg = Joiner.on(sep).join(Iterables.transform(starargs,
              new com.google.common.base.Function<Object, String>() {
                @Override
                public String apply(Object input) {
                  return Printer.str(input);
                }}));
      env.handleEvent(Event.warn(loc, msg));
      return Runtime.NONE;
    }
  };

  @SkylarkSignature(name = "zip",
      doc = "Returns a <code>list</code> of <code>tuple</code>s, where the i-th tuple contains "
          + "the i-th element from each of the argument sequences or iterables. The list has the "
          + "size of the shortest input. With a single iterable argument, it returns a list of "
          + "1-tuples. With no arguments, it returns an empty list. Examples:"
          + "<pre class=\"language-python\">"
          + "zip()  # == []\n"
          + "zip([1, 2])  # == [(1,), (2,)]\n"
          + "zip([1, 2], [3, 4])  # == [(1, 3), (2, 4)]\n"
          + "zip([1, 2], [3, 4, 5])  # == [(1, 3), (2, 4)]</pre>",
      extraPositionals = {@Param(name = "args", doc = "lists to zip")},
      returnType = MutableList.class, useLocation = true, useEnvironment = true)
  private static final BuiltinFunction zip = new BuiltinFunction("zip") {
    public MutableList invoke(SkylarkList args, Location loc, Environment env)
        throws EvalException {
      Iterator<?>[] iterators = new Iterator<?>[args.size()];
      for (int i = 0; i < args.size(); i++) {
        iterators[i] = EvalUtils.toIterable(args.get(i), loc).iterator();
      }
      List<Tuple> result = new ArrayList<>();
      boolean allHasNext;
      do {
        allHasNext = !args.isEmpty();
        List<Object> elem = Lists.newArrayListWithExpectedSize(args.size());
        for (Iterator<?> iterator : iterators) {
          if (iterator.hasNext()) {
            elem.add(iterator.next());
          } else {
            allHasNext = false;
          }
        }
        if (allHasNext) {
          result.add(Tuple.copyOf(elem));
        }
      } while (allHasNext);
      return new MutableList(result, env);
    }
  };

  /**
   * Skylark String module.
   */
  @SkylarkModule(name = "string", doc =
      "A language built-in type to support strings. "
      + "Examples of string literals:<br>"
      + "<pre class=\"language-python\">a = 'abc\\ndef'\n"
      + "b = \"ab'cd\"\n"
      + "c = \"\"\"multiline string\"\"\"\n"
      + "\n"
      + "# Strings support slicing (negative index starts from the end):\n"
      + "x = \"hello\"[2:4]  # \"ll\"\n"
      + "y = \"hello\"[1:-1]  # \"ell\"\n"
      + "z = \"hello\"[:4]  # \"hell\"</pre>"
      + "Strings are iterable and support the <code>in</code> operator. Examples:<br>"
      + "<pre class=\"language-python\">\"bc\" in \"abcd\"   # evaluates to True\n"
      + "x = [s for s in \"abc\"]  # x == [\"a\", \"b\", \"c\"]</pre>\n"
      + "Implicit concatenation of strings is not allowed; use the <code>+</code> "
      + "operator instead.")
  static final class StringModule {}

  /**
   * Skylark Dict module.
   */
  @SkylarkModule(name = "dict", doc =
      "A language built-in type to support dicts. "
      + "Example of dict literal:<br>"
      + "<pre class=\"language-python\">d = {\"a\": 2, \"b\": 5}</pre>"
      + "Use brackets to access elements:<br>"
      + "<pre class=\"language-python\">e = d[\"a\"]   # e == 2</pre>"
      + "Dicts support the <code>+</code> operator to concatenate two dicts. In case of multiple "
      + "keys the second one overrides the first one. Examples:<br>"
      + "<pre class=\"language-python\">"
      + "d = {\"a\" : 1} + {\"b\" : 2}   # d == {\"a\" : 1, \"b\" : 2}\n"
      + "d += {\"c\" : 3}              # d == {\"a\" : 1, \"b\" : 2, \"c\" : 3}\n"
      + "d = d + {\"c\" : 5}           # d == {\"a\" : 1, \"b\" : 2, \"c\" : 5}</pre>"
      + "Since the language doesn't have mutable objects <code>d[\"a\"] = 5</code> automatically "
      + "translates to <code>d = d + {\"a\" : 5}</code>.<br>"
      + "Iterating on a dict is equivalent to iterating on its keys (in sorted order).<br>"
      + "Dicts support the <code>in</code> operator, testing membership in the keyset of the dict. "
      + "Example:<br>"
      + "<pre class=\"language-python\">\"a\" in {\"a\" : 2, \"b\" : 5}   # evaluates as True"
      + "</pre>")
  static final class DictModule {}

  static final List<BaseFunction> buildGlobalFunctions = ImmutableList.<BaseFunction>of(
      bool, dict, enumerate, int_, len, list, minus, range, repr, select, sorted, str, zip);

  static final List<BaseFunction> skylarkGlobalFunctions =
      ImmutableList.<BaseFunction>builder()
      .addAll(buildGlobalFunctions)
      .add(dir, fail, getattr, hasattr, print, set, struct, type)
      .build();


  /**
   * Collect global functions for the validation environment.
   */
  public static void setupValidationEnvironment(Set<String> builtIn) {
    for (BaseFunction function : skylarkGlobalFunctions) {
      builtIn.add(function.getName());
    }
  }

  static {
    SkylarkSignatureProcessor.configureSkylarkFunctions(MethodLibrary.class);
  }
}
