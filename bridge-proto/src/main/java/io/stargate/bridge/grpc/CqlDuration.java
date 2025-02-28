/*
 * Copyright DataStax, Inc.
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
package io.stargate.bridge.grpc;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import java.time.Duration;
import java.time.Period;
import java.time.temporal.ChronoUnit;
import java.time.temporal.Temporal;
import java.time.temporal.TemporalAmount;
import java.time.temporal.TemporalUnit;
import java.time.temporal.UnsupportedTemporalTypeException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A duration, as defined in CQL.
 *
 * <p>It stores months, days, and seconds separately due to the fact that the number of days in a
 * month varies, and a day can have 23 or 25 hours if a daylight saving is involved. As such, this
 * type differs from {@link Duration} (which only represents an amount between two points in time,
 * regardless of the calendar).
 *
 * <p>Note: this class was copied from the Datastax Java driver, also licensed under the Apache
 * license version 2.0.
 */
public final class CqlDuration implements TemporalAmount {

  static final long NANOS_PER_MICRO = 1000L;
  static final long NANOS_PER_MILLI = 1000 * NANOS_PER_MICRO;
  static final long NANOS_PER_SECOND = 1000 * NANOS_PER_MILLI;
  static final long NANOS_PER_MINUTE = 60 * NANOS_PER_SECOND;
  static final long NANOS_PER_HOUR = 60 * NANOS_PER_MINUTE;
  static final int DAYS_PER_WEEK = 7;
  static final int MONTHS_PER_YEAR = 12;

  /** The Regexp used to parse the duration provided as String. */
  private static final Pattern STANDARD_PATTERN =
      Pattern.compile(
          "\\G(\\d+)(y|Y|mo|MO|mO|Mo|w|W|d|D|h|H|s|S|ms|MS|mS|Ms|us|US|uS|Us|µs|µS|ns|NS|nS|Ns|m|M)");

  /**
   * The Regexp used to parse the duration when provided in the ISO 8601 format with designators.
   */
  private static final Pattern ISO8601_PATTERN =
      Pattern.compile("P((\\d+)Y)?((\\d+)M)?((\\d+)D)?(T((\\d+)H)?((\\d+)M)?((\\d+)S)?)?");

  /**
   * The Regexp used to parse the duration when provided in the ISO 8601 format with designators.
   */
  private static final Pattern ISO8601_WEEK_PATTERN = Pattern.compile("P(\\d+)W");

  /** The Regexp used to parse the duration when provided in the ISO 8601 alternative format. */
  private static final Pattern ISO8601_ALTERNATIVE_PATTERN =
      Pattern.compile("P(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2}):(\\d{2})");

  private static final ImmutableList<TemporalUnit> TEMPORAL_UNITS =
      ImmutableList.of(ChronoUnit.MONTHS, ChronoUnit.DAYS, ChronoUnit.NANOS);

  private final int months;
  private final int days;
  private final long nanoseconds;

  private CqlDuration(int months, int days, long nanoseconds) {
    // Makes sure that all the values are negative if one of them is
    if ((months < 0 || days < 0 || nanoseconds < 0)
        && (months > 0 || days > 0 || nanoseconds > 0)) {
      throw new IllegalArgumentException(
          String.format(
              "All values must be either negative or positive, got %d months, %d days, %d nanoseconds",
              months, days, nanoseconds));
    }
    this.months = months;
    this.days = days;
    this.nanoseconds = nanoseconds;
  }

  /**
   * Creates a duration with the given number of months, days and nanoseconds.
   *
   * <p>A duration can be negative. In this case, all the non zero values must be negative.
   *
   * @param months the number of months
   * @param days the number of days
   * @param nanoseconds the number of nanoseconds
   * @throws IllegalArgumentException if the values are not all negative or all positive
   */
  public static CqlDuration newInstance(int months, int days, long nanoseconds) {
    return new CqlDuration(months, days, nanoseconds);
  }

  /**
   * Converts a <code>String</code> into a duration.
   *
   * <p>The accepted formats are:
   *
   * <ul>
   *   <li>multiple digits followed by a time unit like: 12h30m where the time unit can be:
   *       <ul>
   *         <li>{@code y}: years
   *         <li>{@code mo}: months
   *         <li>{@code w}: weeks
   *         <li>{@code d}: days
   *         <li>{@code h}: hours
   *         <li>{@code m}: minutes
   *         <li>{@code s}: seconds
   *         <li>{@code ms}: milliseconds
   *         <li>{@code us} or {@code µs}: microseconds
   *         <li>{@code ns}: nanoseconds
   *       </ul>
   *   <li>ISO 8601 format: P[n]Y[n]M[n]DT[n]H[n]M[n]S or P[n]W
   *   <li>ISO 8601 alternative format: P[YYYY]-[MM]-[DD]T[hh]:[mm]:[ss]
   * </ul>
   *
   * @param input the <code>String</code> to convert
   */
  public static CqlDuration from(String input) {
    boolean isNegative = input.startsWith("-");
    String source = isNegative ? input.substring(1) : input;

    if (source.startsWith("P")) {
      if (source.endsWith("W")) {
        return parseIso8601WeekFormat(isNegative, source);
      }
      if (source.contains("-")) {
        return parseIso8601AlternativeFormat(isNegative, source);
      }
      return parseIso8601Format(isNegative, source);
    }
    return parseStandardFormat(isNegative, source);
  }

  private static CqlDuration parseIso8601Format(boolean isNegative, String source) {
    Matcher matcher = ISO8601_PATTERN.matcher(source);
    if (!matcher.matches())
      throw new IllegalArgumentException(
          String.format("Unable to convert '%s' to a duration", source));

    Builder builder = new Builder(isNegative);
    if (matcher.group(1) != null) {
      builder.addYears(groupAsLong(matcher, 2));
    }
    if (matcher.group(3) != null) {
      builder.addMonths(groupAsLong(matcher, 4));
    }
    if (matcher.group(5) != null) {
      builder.addDays(groupAsLong(matcher, 6));
    }
    // Checks if the String contains time information
    if (matcher.group(7) != null) {
      if (matcher.group(8) != null) {
        builder.addHours(groupAsLong(matcher, 9));
      }
      if (matcher.group(10) != null) {
        builder.addMinutes(groupAsLong(matcher, 11));
      }
      if (matcher.group(12) != null) {
        builder.addSeconds(groupAsLong(matcher, 13));
      }
    }
    return builder.build();
  }

  private static CqlDuration parseIso8601AlternativeFormat(boolean isNegative, String source) {
    Matcher matcher = ISO8601_ALTERNATIVE_PATTERN.matcher(source);
    if (!matcher.matches()) {
      throw new IllegalArgumentException(
          String.format("Unable to convert '%s' to a duration", source));
    }
    return new Builder(isNegative)
        .addYears(groupAsLong(matcher, 1))
        .addMonths(groupAsLong(matcher, 2))
        .addDays(groupAsLong(matcher, 3))
        .addHours(groupAsLong(matcher, 4))
        .addMinutes(groupAsLong(matcher, 5))
        .addSeconds(groupAsLong(matcher, 6))
        .build();
  }

  private static CqlDuration parseIso8601WeekFormat(boolean isNegative, String source) {
    Matcher matcher = ISO8601_WEEK_PATTERN.matcher(source);
    if (!matcher.matches()) {
      throw new IllegalArgumentException(
          String.format("Unable to convert '%s' to a duration", source));
    }
    return new Builder(isNegative).addWeeks(groupAsLong(matcher, 1)).build();
  }

  private static CqlDuration parseStandardFormat(boolean isNegative, String source) {
    Matcher matcher = STANDARD_PATTERN.matcher(source);
    if (!matcher.find()) {
      throw new IllegalArgumentException(
          String.format("Unable to convert '%s' to a duration", source));
    }
    Builder builder = new Builder(isNegative);
    boolean done;

    do {
      long number = groupAsLong(matcher, 1);
      String symbol = matcher.group(2);
      add(builder, number, symbol);
      done = matcher.end() == source.length();
    } while (matcher.find());

    if (!done) {
      throw new IllegalArgumentException(
          String.format("Unable to convert '%s' to a duration", source));
    }
    return builder.build();
  }

  private static long groupAsLong(Matcher matcher, int group) {
    return Long.parseLong(matcher.group(group));
  }

  private static Builder add(Builder builder, long number, String symbol) {
    String s = symbol.toLowerCase(Locale.ROOT);
    if (s.equals("y")) {
      return builder.addYears(number);
    } else if (s.equals("mo")) {
      return builder.addMonths(number);
    } else if (s.equals("w")) {
      return builder.addWeeks(number);
    } else if (s.equals("d")) {
      return builder.addDays(number);
    } else if (s.equals("h")) {
      return builder.addHours(number);
    } else if (s.equals("m")) {
      return builder.addMinutes(number);
    } else if (s.equals("s")) {
      return builder.addSeconds(number);
    } else if (s.equals("ms")) {
      return builder.addMillis(number);
    } else if (s.equals("us") || s.equals("µs")) {
      return builder.addMicros(number);
    } else if (s.equals("ns")) {
      return builder.addNanos(number);
    }
    throw new IllegalArgumentException(String.format("Unknown duration symbol '%s'", symbol));
  }

  /**
   * Appends the result of the division to the specified builder if the dividend is not zero.
   *
   * @param builder the builder to append to
   * @param dividend the dividend
   * @param divisor the divisor
   * @param unit the time unit to append after the result of the division
   * @return the remainder of the division
   */
  private static long append(StringBuilder builder, long dividend, long divisor, String unit) {
    if (dividend == 0 || dividend < divisor) {
      return dividend;
    }
    builder.append(dividend / divisor).append(unit);
    return dividend % divisor;
  }

  /**
   * Returns the number of months in this duration.
   *
   * @return the number of months in this duration.
   */
  public int getMonths() {
    return months;
  }

  /**
   * Returns the number of days in this duration.
   *
   * @return the number of days in this duration.
   */
  public int getDays() {
    return days;
  }

  /**
   * Returns the number of nanoseconds in this duration.
   *
   * @return the number of months in this duration.
   */
  public long getNanoseconds() {
    return nanoseconds;
  }

  /**
   * {@inheritDoc}
   *
   * <p>This implementation converts the months and days components to a {@link Period}, and the
   * nanosecond component to a {@link Duration}, and adds those two amounts to the temporal object.
   * Therefore the chronology of the temporal must be either the ISO chronology or null.
   *
   * @see Period#addTo(Temporal)
   * @see Duration#addTo(Temporal)
   */
  @Override
  public Temporal addTo(Temporal temporal) {
    return temporal.plus(Period.of(0, months, days)).plus(Duration.ofNanos(nanoseconds));
  }

  /**
   * {@inheritDoc}
   *
   * <p>This implementation converts the months and days components to a {@link Period}, and the
   * nanosecond component to a {@link Duration}, and subtracts those two amounts to the temporal
   * object. Therefore the chronology of the temporal must be either the ISO chronology or null.
   *
   * @see Period#subtractFrom(Temporal)
   * @see Duration#subtractFrom(Temporal)
   */
  @Override
  public Temporal subtractFrom(Temporal temporal) {
    return temporal.minus(Period.of(0, months, days)).minus(Duration.ofNanos(nanoseconds));
  }

  @Override
  public long get(TemporalUnit unit) {
    if (unit == ChronoUnit.MONTHS) {
      return months;
    } else if (unit == ChronoUnit.DAYS) {
      return days;
    } else if (unit == ChronoUnit.NANOS) {
      return nanoseconds;
    } else {
      throw new UnsupportedTemporalTypeException("Unsupported unit: " + unit);
    }
  }

  @Override
  public List<TemporalUnit> getUnits() {
    return TEMPORAL_UNITS;
  }

  @Override
  public boolean equals(Object other) {
    if (other == this) {
      return true;
    } else if (other instanceof CqlDuration) {
      CqlDuration that = (CqlDuration) other;
      return this.days == that.days
          && this.months == that.months
          && this.nanoseconds == that.nanoseconds;
    } else {
      return false;
    }
  }

  @Override
  public int hashCode() {
    return Objects.hash(days, months, nanoseconds);
  }

  @Override
  public String toString() {
    StringBuilder builder = new StringBuilder();

    if (months < 0 || days < 0 || nanoseconds < 0) {
      builder.append('-');
    }
    long remainder = append(builder, Math.abs(months), MONTHS_PER_YEAR, "y");
    append(builder, remainder, 1, "mo");

    append(builder, Math.abs(days), 1, "d");

    if (nanoseconds != 0) {
      remainder = append(builder, Math.abs(nanoseconds), NANOS_PER_HOUR, "h");
      remainder = append(builder, remainder, NANOS_PER_MINUTE, "m");
      remainder = append(builder, remainder, NANOS_PER_SECOND, "s");
      remainder = append(builder, remainder, NANOS_PER_MILLI, "ms");
      remainder = append(builder, remainder, NANOS_PER_MICRO, "us");
      append(builder, remainder, 1, "ns");
    }
    return builder.toString();
  }

  private static class Builder {
    private final boolean isNegative;
    private int months;
    private int days;
    private long nanoseconds;

    /** We need to make sure that the values for each units are provided in order. */
    private int currentUnitIndex;

    public Builder(boolean isNegative) {
      this.isNegative = isNegative;
    }

    /**
     * Adds the specified amount of years.
     *
     * @param numberOfYears the number of years to add.
     * @return this {@code Builder}
     */
    public Builder addYears(long numberOfYears) {
      validateOrder(1);
      validateMonths(numberOfYears, MONTHS_PER_YEAR);
      // Cast to avoid http://errorprone.info/bugpattern/NarrowingCompoundAssignment
      // We could also change the method to accept an int, but keeping long allows us to keep the
      // calling code generic.
      months += (int) numberOfYears * MONTHS_PER_YEAR;
      return this;
    }

    /**
     * Adds the specified amount of months.
     *
     * @param numberOfMonths the number of months to add.
     * @return this {@code Builder}
     */
    public Builder addMonths(long numberOfMonths) {
      validateOrder(2);
      validateMonths(numberOfMonths, 1);
      months += (int) numberOfMonths;
      return this;
    }

    /**
     * Adds the specified amount of weeks.
     *
     * @param numberOfWeeks the number of weeks to add.
     * @return this {@code Builder}
     */
    public Builder addWeeks(long numberOfWeeks) {
      validateOrder(3);
      validateDays(numberOfWeeks, DAYS_PER_WEEK);
      days += (int) numberOfWeeks * DAYS_PER_WEEK;
      return this;
    }

    /**
     * Adds the specified amount of days.
     *
     * @param numberOfDays the number of days to add.
     * @return this {@code Builder}
     */
    public Builder addDays(long numberOfDays) {
      validateOrder(4);
      validateDays(numberOfDays, 1);
      days += (int) numberOfDays;
      return this;
    }

    /**
     * Adds the specified amount of hours.
     *
     * @param numberOfHours the number of hours to add.
     * @return this {@code Builder}
     */
    public Builder addHours(long numberOfHours) {
      validateOrder(5);
      validateNanos(numberOfHours, NANOS_PER_HOUR);
      nanoseconds += numberOfHours * NANOS_PER_HOUR;
      return this;
    }

    /**
     * Adds the specified amount of minutes.
     *
     * @param numberOfMinutes the number of minutes to add.
     * @return this {@code Builder}
     */
    public Builder addMinutes(long numberOfMinutes) {
      validateOrder(6);
      validateNanos(numberOfMinutes, NANOS_PER_MINUTE);
      nanoseconds += numberOfMinutes * NANOS_PER_MINUTE;
      return this;
    }

    /**
     * Adds the specified amount of seconds.
     *
     * @param numberOfSeconds the number of seconds to add.
     * @return this {@code Builder}
     */
    public Builder addSeconds(long numberOfSeconds) {
      validateOrder(7);
      validateNanos(numberOfSeconds, NANOS_PER_SECOND);
      nanoseconds += numberOfSeconds * NANOS_PER_SECOND;
      return this;
    }

    /**
     * Adds the specified amount of milliseconds.
     *
     * @param numberOfMillis the number of milliseconds to add.
     * @return this {@code Builder}
     */
    public Builder addMillis(long numberOfMillis) {
      validateOrder(8);
      validateNanos(numberOfMillis, NANOS_PER_MILLI);
      nanoseconds += numberOfMillis * NANOS_PER_MILLI;
      return this;
    }

    /**
     * Adds the specified amount of microseconds.
     *
     * @param numberOfMicros the number of microseconds to add.
     * @return this {@code Builder}
     */
    public Builder addMicros(long numberOfMicros) {
      validateOrder(9);
      validateNanos(numberOfMicros, NANOS_PER_MICRO);
      nanoseconds += numberOfMicros * NANOS_PER_MICRO;
      return this;
    }

    /**
     * Adds the specified amount of nanoseconds.
     *
     * @param numberOfNanos the number of nanoseconds to add.
     * @return this {@code Builder}
     */
    public Builder addNanos(long numberOfNanos) {
      validateOrder(10);
      validateNanos(numberOfNanos, 1);
      nanoseconds += numberOfNanos;
      return this;
    }

    /**
     * Validates that the total number of months can be stored.
     *
     * @param units the number of units that need to be added
     * @param monthsPerUnit the number of days per unit
     */
    private void validateMonths(long units, int monthsPerUnit) {
      validate(units, (Integer.MAX_VALUE - months) / monthsPerUnit, "months");
    }

    /**
     * Validates that the total number of days can be stored.
     *
     * @param units the number of units that need to be added
     * @param daysPerUnit the number of days per unit
     */
    private void validateDays(long units, int daysPerUnit) {
      validate(units, (Integer.MAX_VALUE - days) / daysPerUnit, "days");
    }

    /**
     * Validates that the total number of nanoseconds can be stored.
     *
     * @param units the number of units that need to be added
     * @param nanosPerUnit the number of nanoseconds per unit
     */
    private void validateNanos(long units, long nanosPerUnit) {
      validate(units, (Long.MAX_VALUE - nanoseconds) / nanosPerUnit, "nanoseconds");
    }

    /**
     * Validates that the specified amount is less than the limit.
     *
     * @param units the number of units to check
     * @param limit the limit on the number of units
     * @param unitName the unit name
     */
    private void validate(long units, long limit, String unitName) {
      Preconditions.checkArgument(
          units <= limit,
          "Invalid duration. The total number of %s must be less or equal to %s",
          unitName,
          Integer.MAX_VALUE);
    }

    /**
     * Validates that the duration values are added in the proper order.
     *
     * @param unitIndex the unit index (e.g. years=1, months=2, ...)
     */
    private void validateOrder(int unitIndex) {
      if (unitIndex == currentUnitIndex) {
        throw new IllegalArgumentException(
            String.format(
                "Invalid duration. The %s are specified multiple times", getUnitName(unitIndex)));
      }
      if (unitIndex <= currentUnitIndex) {
        throw new IllegalArgumentException(
            String.format(
                "Invalid duration. The %s should be after %s",
                getUnitName(currentUnitIndex), getUnitName(unitIndex)));
      }
      currentUnitIndex = unitIndex;
    }

    /**
     * Returns the name of the unit corresponding to the specified index.
     *
     * @param unitIndex the unit index
     * @return the name of the unit corresponding to the specified index.
     */
    private String getUnitName(int unitIndex) {
      switch (unitIndex) {
        case 1:
          return "years";
        case 2:
          return "months";
        case 3:
          return "weeks";
        case 4:
          return "days";
        case 5:
          return "hours";
        case 6:
          return "minutes";
        case 7:
          return "seconds";
        case 8:
          return "milliseconds";
        case 9:
          return "microseconds";
        case 10:
          return "nanoseconds";
        default:
          throw new AssertionError("unknown unit index: " + unitIndex);
      }
    }

    public CqlDuration build() {
      return isNegative
          ? new CqlDuration(-months, -days, -nanoseconds)
          : new CqlDuration(months, days, nanoseconds);
    }
  }
}
