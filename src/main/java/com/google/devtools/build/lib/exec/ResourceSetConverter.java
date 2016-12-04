package com.google.devtools.build.lib.exec;

import java.util.Iterator;
import java.util.NoSuchElementException;

import com.google.common.base.Splitter;
import com.google.devtools.build.lib.util.resources.ResourceSet;
import com.google.devtools.common.options.Converter;
import com.google.devtools.common.options.OptionsParsingException;

public class ResourceSetConverter implements Converter<ResourceSet> {
  private static final Splitter SPLITTER = Splitter.on(',');

  @Override
  public ResourceSet convert(String input) throws OptionsParsingException {
    Iterator<String> values = SPLITTER.split(input).iterator();
    try {
      double memoryMb = Double.parseDouble(values.next());
      double cpuUsage = Double.parseDouble(values.next());
      double ioUsage = Double.parseDouble(values.next());
      if (values.hasNext()) {
        throw new OptionsParsingException("Expected exactly 3 comma-separated float values");
      }
      if (memoryMb <= 0.0 || cpuUsage <= 0.0 || ioUsage <= 0.0) {
        throw new OptionsParsingException("All resource values must be positive");
      }
      return ResourceSet.create(memoryMb, cpuUsage, ioUsage, Integer.MAX_VALUE);
    } catch (NumberFormatException | NoSuchElementException nfe) {
      throw new OptionsParsingException("Expected exactly 3 comma-separated float values", nfe);
    }
  }

  @Override
  public String getTypeDescription() {
    return "comma-separated available amount of RAM (in MB), CPU (in cores) and "
        + "available I/O (1.0 being average workstation)";
  }

}