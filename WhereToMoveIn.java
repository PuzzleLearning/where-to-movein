import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.function.Supplier;

/**
 * Where to move in: the spot on a straight street that is closest, in total, to everyone
 * who lives on it.
 *
 * <p>Nine friends live along one street, at 1.1, 2.3, 3.0, 4.2, 5.1, 6.3, 13.4, 19.1 and
 * 27.5 km from its left end. Where should you rent a flat so that the distances to all
 * nine of them add up to as little as possible?
 *
 * <p>The answer is the median, the fifth house of nine, and this program finds it three
 * ways that share no reasoning with each other:
 *
 * <ul>
 *   <li>{@link #scan}: walk the street a metre at a time and add up every distance at
 *       every step. No theory at all, which is what makes it a good referee.</li>
 *   <li>{@link #tryEveryHouse}: the total is a chain of straight lines with a corner at
 *       each house, so its lowest point is at a house. Try only those.</li>
 *   <li>{@link #median}: sort, take the middle. The outermost two friends cost the gap
 *       between them from anywhere in between, and so does every pair inside them, so the
 *       best spot is inside the innermost pair.</li>
 * </ul>
 *
 * <p>It also scores the two other things people mean by "the middle", the mean and the
 * midpoint between the two end houses. Each of the three turns out to be the best answer
 * to a different question.
 *
 * <p>Run it straight from source, no build step required (JEP 330):
 *
 * <pre>
 *   java WhereToMoveIn.java                     # the puzzle from README.md
 *   java WhereToMoveIn.java 1.1 2.3 3.0 4.2     # your own street, in km
 *   java WhereToMoveIn.java --random 50 -q      # fifty houses at random
 *   java WhereToMoveIn.java --help
 * </pre>
 *
 * <p>The tables go to stdout, the summary to stderr. The exit code is 0 when every method
 * agrees and the answer checks out, 1 when a cross-check failed, and 2 on a bad command
 * line.
 *
 * <p>Requires Java 17 or newer. Nothing outside {@code java.base} is used.
 */
public final class WhereToMoveIn {

    /** Positions live in whole metres, so every sum and every comparison is exact. */
    static final int METRES_PER_KM = 1_000;

    /** The street from the puzzle, exactly as README.md states it. */
    static final List<House> PUZZLE = List.of(
            new House("A", 1_100),
            new House("B", 2_300),
            new House("C", 3_000),
            new House("D", 4_200),
            new House("E", 5_100),
            new House("F", 6_300),
            new House("G", 13_400),
            new House("H", 19_100),
            new House("I", 27_500));

    static final long DEFAULT_SEED = 2026L;
    static final int DEFAULT_LENGTH = 30 * METRES_PER_KM;

    /** Random houses stand on a 100 m grid, like the puzzle's, so neighbours can share a spot. */
    static final int RANDOM_GRID = 100;

    /**
     * Limits that keep every total comfortably inside a {@code long}, including the mean,
     * which is compared exactly as a fraction with the house count as its denominator.
     */
    static final int MAX_HOUSES = 100_000;
    static final int MAX_POSITION = 10_000 * METRES_PER_KM;

    /**
     * How many single distances a brute-force method may add up before it declines. The
     * puzzle needs a quarter of a million; a hundred thousand random houses would need ten
     * billion, and the median answers that in a few milliseconds anyway.
     */
    static final long MAX_EVALUATIONS = 200_000_000L;

    private WhereToMoveIn() {
        // Static entry point only; nothing here is worth instantiating.
    }

    // ---------------------------------------------------------------------- the data

    /**
     * One friend's house.
     *
     * @param name   a label, "A" to "I" in the puzzle
     * @param metres how far along the street it stands, from the left end
     */
    record House(String name, int metres) {}

    /**
     * Where to move in, as one method sees it.
     *
     * <p>The best spot is not always a single point. With an even number of houses every
     * spot between the middle two is exactly as good as every other, so an answer is a
     * stretch of street, {@code from} to {@code to}, which is one point when they are equal.
     *
     * @param from  the leftmost best spot, in metres
     * @param to    the rightmost best spot, in metres
     * @param total the distances from any of them to every house, added up, in metres
     */
    record Optimum(int from, int to, long total) {

        boolean isPoint() {
            return from == to;
        }
    }

    /** One method's answer and how long it took; {@code optimum} is null if it declined. */
    record Result(String label, Optimum optimum, long nanos) {}

    /** The quantity being minimised, {@code Σ|x − hᵢ|}, in metres. */
    static long totalDistance(int[] houses, long x) {
        long total = 0;
        for (int house : houses) {
            total += Math.abs(x - house);
        }
        return total;
    }

    // ------------------------------------------------------------ the three methods

    /**
     * Walk the street a metre at a time, from its left end to the farthest house, adding
     * up every distance at every step.
     *
     * <p>This is the 2013 program's idea, kept because it assumes nothing about where the
     * answer is or how many answers there are. It is also why positions are whole metres.
     * The original stepped along the street in doubles, {@code x += 0.001}, and a million
     * additions later the rounding had piled up far enough to run its loop a million and
     * one times. Counting metres in an {@code int} cannot drift.
     *
     * <p>Nothing past the farthest house can win, because every step further right is a
     * step further from everyone. The original searched on to 1,000 km regardless.
     *
     * @return the first and last metre at which the smallest total turns up, or
     *         {@code null} if that would take more than {@link #MAX_EVALUATIONS} distances
     */
    static Optimum scan(int[] houses) {
        int end = Arrays.stream(houses).max().orElseThrow();
        if ((end + 1L) * houses.length > MAX_EVALUATIONS) {
            return null;
        }
        long best = Long.MAX_VALUE;
        int from = 0;
        int to = 0;
        for (int x = 0; x <= end; x++) {
            long total = totalDistance(houses, x);
            if (total < best) {
                best = total;
                from = x;
                to = x;
            } else if (total == best) {
                to = x;
            }
        }
        return new Optimum(from, to, best);
    }

    /**
     * Try each house as the spot, and nowhere else.
     *
     * <p>Between two neighbouring houses the total changes at a constant rate. Every step
     * to the right takes you one step further from each friend behind you and one step
     * closer to each friend ahead, and nobody changes sides until you pass a house. So
     * the total is a chain of straight lines with a corner at every house, and a chain of
     * straight lines can only bottom out at a corner. If two corners tie for the lowest,
     * the whole stretch between them is flat and just as good.
     *
     * @return the span of the best houses, or {@code null} past {@link #MAX_EVALUATIONS}
     */
    static Optimum tryEveryHouse(int[] houses) {
        if ((long) houses.length * houses.length > MAX_EVALUATIONS) {
            return null;
        }
        long best = Long.MAX_VALUE;
        int from = Integer.MAX_VALUE;
        int to = Integer.MIN_VALUE;
        for (int house : houses) {
            long total = totalDistance(houses, house);
            if (total < best) {
                best = total;
                from = house;
                to = house;
            } else if (total == best) {
                from = Math.min(from, house);
                to = Math.max(to, house);
            }
        }
        return new Optimum(from, to, best);
    }

    /**
     * Sort the houses and take the middle one, or, with an even count, anywhere between
     * the middle two.
     *
     * <p>Why: pair the leftmost friend with the rightmost. From anywhere between them the
     * two of them cost exactly the gap between their houses, and from anywhere outside
     * they cost more. Pair the next two in, and the next, working inwards. Every spot on
     * the street pays at least the sum of all those gaps, and a spot inside the innermost
     * pair, which is inside every pair, pays exactly that. The innermost pair is the
     * middle of the sorted list.
     *
     * <p>The total is added up the same way, as the sum of the gaps. It never calls
     * {@link #totalDistance}, so the two can be checked against each other.
     */
    static Optimum median(int[] houses) {
        int[] sorted = houses.clone();
        Arrays.sort(sorted);
        int n = sorted.length;
        long total = 0;
        for (int i = 0; i < n / 2; i++) {
            total += sorted[n - 1 - i] - sorted[i];
        }
        return new Optimum(sorted[(n - 1) / 2], sorted[n / 2], total);
    }

    // ---------------------------------------------------------------- other middles

    /**
     * A spot that need not be on a whole metre: {@code numerator / denominator} metres.
     *
     * <p>The mean of the puzzle's nine houses is 82 km divided by 9. Rounding it to the
     * nearest metre before scoring it would be scoring a different spot, so it is kept as a
     * fraction, and its total distance is compared with the optimum exactly.
     */
    record Middle(String label, long numerator, long denominator) {

        double metres() {
            return (double) numerator / denominator;
        }

        /** {@code Σ|x − hᵢ|} times the denominator, which keeps it a whole number. */
        long scaledTotal(int[] houses) {
            long total = 0;
            for (int house : houses) {
                total += Math.abs(numerator - denominator * house);
            }
            return total;
        }

        /** Total distance, in km. */
        double total(int[] houses) {
            return (double) scaledTotal(houses) / denominator / METRES_PER_KM;
        }

        /** Squared distances added up, in km². */
        double squares(int[] houses) {
            double sum = 0;
            for (int house : houses) {
                double km = (numerator - (double) denominator * house) / denominator / METRES_PER_KM;
                sum += km * km;
            }
            return sum;
        }

        /** The distance to the farthest house, in km. */
        double farthest(int[] houses) {
            long worst = 0;
            for (int house : houses) {
                worst = Math.max(worst, Math.abs(numerator - denominator * house));
            }
            return (double) worst / denominator / METRES_PER_KM;
        }

        /** The answers to {@link #QUESTIONS}, in order. */
        double[] scores(int[] houses) {
            return new double[] {total(houses), squares(houses), farthest(houses)};
        }
    }

    /** What each of the three middles is best at, in the order {@link #middles} lists them. */
    static final List<String> QUESTIONS = List.of("total distance", "sum of squares", "farthest house");

    /**
     * The three spots that get called "the middle", and the question each one answers.
     *
     * <p>The median minimises the total distance, the mean minimises the total of the
     * squared distances, and the midrange, halfway between the two end houses, minimises
     * the distance to the farthest one. On a symmetric street all three coincide.
     */
    static List<Middle> middles(int[] houses) {
        int[] sorted = houses.clone();
        Arrays.sort(sorted);
        int n = sorted.length;
        long sum = 0;
        for (int house : sorted) {
            sum += house;
        }
        return List.of(
                new Middle("median", (long) sorted[(n - 1) / 2] + sorted[n / 2], 2),
                new Middle("mean", sum, n),
                new Middle("midrange", (long) sorted[0] + sorted[n - 1], 2));
    }

    // -------------------------------------------------------------------- formatting

    /** "1 house", "9 houses". */
    static String plural(long count, String noun) {
        return String.format(Locale.ROOT, "%,d %s%s", count, noun, count == 1 ? "" : "s");
    }

    /** Whole metres as kilometres, exactly, to the given number of decimals. */
    static String km(long metres, int decimals) {
        return String.format(Locale.ROOT, "%,." + decimals + "f km", BigDecimal.valueOf(metres, 3));
    }

    /** One decimal if every house is on a 100 m grid, as in the puzzle; more if it is not. */
    static int decimals(int[] houses) {
        int decimals = 1;
        for (int house : houses) {
            if (house % 10 != 0) {
                return 3;
            }
            if (house % 100 != 0) {
                decimals = 2;
            }
        }
        return decimals;
    }

    /** "5.1 km", or "4.2 – 5.1 km" when the answer is a stretch of street. */
    static String spot(Optimum optimum, int decimals) {
        if (optimum.isPoint()) {
            return km(optimum.from(), decimals);
        }
        return String.format(Locale.ROOT, "%,." + decimals + "f – %s",
                BigDecimal.valueOf(optimum.from(), 3), km(optimum.to(), decimals));
    }

    /** "E", "D and E" when two friends share a spot, "AB, AC and 330 others" on a crowded one. */
    static String residents(List<House> street, int metres) {
        List<String> names = street.stream()
                .filter(house -> house.metres() == metres)
                .map(House::name)
                .toList();
        return switch (names.size()) {
            case 0 -> "nobody";
            case 1 -> names.get(0);
            case 2 -> names.get(0) + " and " + names.get(1);
            default -> names.get(0) + ", " + names.get(1) + " and "
                    + plural(names.size() - 2L, "other");
        };
    }

    /** "all 9 houses", or the right words for a street of one or two. */
    static String everyone(int count) {
        return switch (count) {
            case 1 -> "the only house";
            case 2 -> "both houses";
            default -> "all " + plural(count, "house");
        };
    }

    /** "+17.3%", without claiming "+0.0%" for a difference that is small but real. */
    static String percent(double share) {
        return share < 0.0005 ? "<0.1%" : String.format(Locale.ROOT, "+%.1f%%", 100 * share);
    }

    /** Every house, and how far it is from the chosen spot. */
    static String houseTable(List<House> street, int spot, long total, int decimals) {
        int width = "house".length();
        for (House house : street) {
            width = Math.max(width, house.name().length());
        }
        String header = "distance from " + km(spot, decimals);
        String row = "%-" + width + "s  %14s  %" + header.length() + "s%n";

        StringBuilder out = new StringBuilder();
        out.append(String.format(Locale.ROOT, row, "house", "lives at", header));
        for (House house : street) {
            out.append(String.format(Locale.ROOT, row,
                    house.name(),
                    km(house.metres(), decimals),
                    km(Math.abs(house.metres() - spot), decimals)));
        }
        out.append(String.format(Locale.ROOT, row, "total", "", km(total, decimals)));
        return out.toString();
    }

    /** One row per method that ran. */
    static String resultsTable(List<Result> results, int decimals) {
        StringBuilder out = new StringBuilder();
        String row = "%-18s %20s %16s %10s%n";
        out.append(String.format(Locale.ROOT, row, "method", "best spot", "total distance", "time"));
        out.append("-".repeat(67)).append(System.lineSeparator());
        for (Result r : results) {
            out.append(String.format(Locale.ROOT, row,
                    r.label(),
                    spot(r.optimum(), decimals),
                    km(r.optimum().total(), decimals),
                    String.format(Locale.ROOT, "%.1fms", r.nanos() / 1e6)));
        }
        return out.toString();
    }

    /**
     * The three middles against the three questions, with the lowest number in each
     * column starred. The stars fall on the diagonal, because each middle wins its own
     * question; {@link #verdict} fails the run if they ever do not.
     */
    static String middlesTable(List<Middle> middles, int[] houses) {
        double[][] scores = new double[middles.size()][];
        double[] lowest = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE};
        for (int i = 0; i < middles.size(); i++) {
            scores[i] = middles.get(i).scores(houses);
            for (int c = 0; c < QUESTIONS.size(); c++) {
                lowest[c] = Math.min(lowest[c], scores[i][c]);
            }
        }

        StringBuilder out = new StringBuilder();
        String row = "%-10s %12s %18s %20s %18s";
        out.append(String.format(Locale.ROOT, row, "middle", "where",
                QUESTIONS.get(0) + "  ", QUESTIONS.get(1) + "  ", QUESTIONS.get(2) + "  ")
                .stripTrailing()).append(System.lineSeparator());
        out.append("-".repeat(82)).append(System.lineSeparator());
        for (int i = 0; i < middles.size(); i++) {
            out.append(String.format(Locale.ROOT, row,
                    middles.get(i).label(),
                    String.format(Locale.ROOT, "%,.3f km", middles.get(i).metres() / METRES_PER_KM),
                    starred("%,.3f km", scores[i][0], lowest[0]),
                    starred("%,.3f km²", scores[i][1], lowest[1]),
                    starred("%,.3f km", scores[i][2], lowest[2]))
                    .stripTrailing()).append(System.lineSeparator());
        }
        out.append("* the lowest in its column").append(System.lineSeparator());
        return out.toString();
    }

    /** A score, with a star if it is the column's lowest. */
    private static String starred(String format, double value, double lowest) {
        return String.format(Locale.ROOT, format, value) + (atMost(value, lowest) ? " *" : "  ");
    }

    /**
     * {@code a <= b}, give or take the last few bits of a double. Only the middles'
     * squares and fractions of a metre are ever compared this way; the optimum itself is
     * compared in whole metres.
     */
    static boolean atMost(double a, double b) {
        return a - b <= 1e-9 * Math.max(1.0, Math.abs(b));
    }

    // ------------------------------------------------------------------- the options

    /** Everything the command line can say. */
    static final class Options {
        List<House> street = PUZZLE;
        String source = "from the puzzle in README.md";
        boolean quiet;
    }

    static final String USAGE = """
            usage: java WhereToMoveIn.java [options] [house ...]

            Find the spot on a straight street with the least total distance to every
            house on it, three independent ways, and compare it with the other middles.

              house              a position in km from the left end of the street, as
                                 KM or NAME=KM (default: the nine houses of the puzzle)
              -r, --random N     N houses at random instead, on a 100 m grid
              -l, --length KM    street length for --random (default %d)
              -s, --seed S       seed for --random (default %d)
              -q, --quiet        do not print the table of houses
              -h, --help         show this message

            Tables go to stdout, the summary to stderr. Exit code 0 means every method
            agrees and the answer checks out, 1 means a cross-check failed, 2 means the
            command line was wrong.
            """;

    static String usage() {
        return String.format(Locale.ROOT, USAGE, DEFAULT_LENGTH / METRES_PER_KM, DEFAULT_SEED);
    }

    /**
     * Parse the command line.
     *
     * @throws IllegalArgumentException with a message meant for the user
     */
    static Options parse(String[] args) {
        Options options = new Options();
        List<House> given = new ArrayList<>();
        Integer random = null;
        int length = DEFAULT_LENGTH;
        long seed = DEFAULT_SEED;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "-h", "--help" -> throw new HelpRequested();
                case "-q", "--quiet" -> options.quiet = true;
                case "-r", "--random" -> random = count(arg, value(args, ++i, arg));
                case "-l", "--length" -> length = metres(arg, value(args, ++i, arg));
                case "-s", "--seed" -> {
                    String raw = value(args, ++i, arg);
                    try {
                        seed = Long.parseLong(raw);
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException(arg + " needs a whole number, got " + raw);
                    }
                }
                default -> {
                    if (arg.startsWith("-")) {
                        throw new IllegalArgumentException("unknown option: " + arg);
                    }
                    given.add(house(arg, given.size()));
                }
            }
        }

        if (random != null && !given.isEmpty()) {
            throw new IllegalArgumentException("give either houses or --random, not both");
        }
        if (given.size() > MAX_HOUSES) {
            throw new IllegalArgumentException(
                    String.format(Locale.ROOT, "at most %,d houses, please", MAX_HOUSES));
        }
        if (random != null) {
            options.street = randomStreet(random, length, seed);
            options.source = "placed at random along " + km(length, decimals(new int[] {length}))
                    + " with seed " + seed;
        } else if (!given.isEmpty()) {
            // List them left to right; the sort is stable, so shared spots keep their order.
            given.sort((a, b) -> Integer.compare(a.metres(), b.metres()));
            options.street = List.copyOf(given);
            options.source = "from the command line";
        }
        return options;
    }

    /** "A=1.1" or a bare "1.1", which gets the next free letter. */
    private static House house(String arg, int index) {
        int equals = arg.indexOf('=');
        if (equals < 0) {
            return new House(letters(index), metres("house " + arg, arg));
        }
        String name = arg.substring(0, equals).strip();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("house " + arg + " has an empty name");
        }
        return new House(name, metres("house " + name, arg.substring(equals + 1)));
    }

    /** 0 → "A", 25 → "Z", 26 → "AA", like spreadsheet columns. */
    static String letters(int index) {
        StringBuilder name = new StringBuilder();
        for (int i = index + 1; i > 0; i = (i - 1) / 26) {
            name.insert(0, (char) ('A' + (i - 1) % 26));
        }
        return name.toString();
    }

    /**
     * A distance in km, read exactly into whole metres: "27.5" is 27,500. Anything finer
     * than a metre is refused rather than rounded, so what gets solved is what was typed.
     */
    static int metres(String what, String raw) {
        BigDecimal km;
        try {
            km = new BigDecimal(raw.strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(what + ": not a distance in km: " + raw);
        }
        if (km.signum() < 0) {
            throw new IllegalArgumentException(what + " cannot be negative");
        }
        BigDecimal metres = km.movePointRight(3);
        if (metres.compareTo(BigDecimal.valueOf(MAX_POSITION)) > 0) {
            throw new IllegalArgumentException(what + " is past the end of any street: at most "
                    + km(MAX_POSITION, 0) + ", please");
        }
        try {
            return metres.intValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(what + " is finer than a metre: " + raw);
        }
    }

    private static int count(String option, String raw) {
        int parsed;
        try {
            parsed = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(option + " needs a whole number, got " + raw);
        }
        if (parsed <= 0 || parsed > MAX_HOUSES) {
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "%s must be between 1 and %,d, got %s", option, MAX_HOUSES, raw));
        }
        return parsed;
    }

    private static String value(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException(option + " needs a value");
        }
        return args[index];
    }

    /** Signals {@code --help}, which is a request rather than a mistake. */
    private static final class HelpRequested extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    /**
     * {@code count} houses on a 100 m grid along a street {@code length} metres long,
     * named left to right. Two can land on the same spot, and that is deliberate: a shared
     * spot is the case the original program was least prepared for.
     */
    static List<House> randomStreet(int count, int length, long seed) {
        Random rng = new Random(seed);
        int[] positions = new int[count];
        for (int i = 0; i < count; i++) {
            positions[i] = rng.nextInt(length / RANDOM_GRID + 1) * RANDOM_GRID;
        }
        Arrays.sort(positions);
        List<House> street = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            street.add(new House(letters(i), positions[i]));
        }
        return List.copyOf(street);
    }

    // -------------------------------------------------------------------------- main

    public static void main(String[] args) {
        Options options;
        try {
            options = parse(args);
        } catch (HelpRequested help) {
            System.out.print(usage());
            return;
        } catch (IllegalArgumentException bad) {
            System.err.println("where-to-movein: " + bad.getMessage());
            System.err.println();
            System.err.print(usage());
            System.exit(2);
            return;
        }

        List<House> street = options.street;
        int[] houses = street.stream().mapToInt(House::metres).toArray();
        int decimals = decimals(houses);

        String span = houses[0] == houses[houses.length - 1]
                ? "at " + km(houses[0], decimals)
                : "between " + km(houses[0], decimals) + " and " + km(houses[houses.length - 1], decimals);
        System.err.printf("%s %s, %s.%n", plural(houses.length, "house"), span, options.source);

        List<Result> results = new ArrayList<>();
        for (Result r : List.of(
                time("scan every metre", () -> scan(houses)),
                time("try every house", () -> tryEveryHouse(houses)),
                time("median", () -> median(houses)))) {
            if (r.optimum() == null) {
                System.err.printf(Locale.ROOT,
                        "%s skipped: it would add up more than %,d distances.%n",
                        r.label(), MAX_EVALUATIONS);
            } else {
                results.add(r);
            }
        }
        Optimum best = results.get(0).optimum();
        List<Middle> middles = middles(houses);

        if (!options.quiet) {
            System.out.print(houseTable(street, best.from(), best.total(), decimals));
            System.out.println();
        }
        System.out.print(resultsTable(results, decimals));
        System.out.println();
        System.out.print(middlesTable(middles, houses));

        int status = verdict(results, middles, houses, decimals);
        if (status == 0) {
            summarise(results, middles, street, houses, decimals);
        }
        System.out.flush();
        System.err.flush();
        System.exit(status);
    }

    /**
     * Grade the run.
     *
     * <p>Four things can only happen if something is broken: two methods disagreeing, an
     * answer whose total is not what its distances add up to, a middle that beats the
     * optimum, and a middle that loses its own question to another. Each is a bug in this
     * file, not an interesting result, so it earns a non-zero exit.
     *
     * <p>The second check doubles as a certificate that works even when only the median
     * could run. The total distance is convex, so a stretch of street whose two ends score
     * the claimed total, and which climbs a metre further out on both sides, cannot be
     * beaten anywhere else.
     */
    private static int verdict(List<Result> results, List<Middle> middles, int[] houses, int decimals) {
        int problems = 0;
        Result first = results.get(0);
        for (Result r : results) {
            Optimum o = r.optimum();
            if (!o.equals(first.optimum())) {
                System.err.printf("FAIL: %s says %s for %s, but %s says %s for %s.%n",
                        r.label(), spot(o, decimals), km(o.total(), decimals),
                        first.label(), spot(first.optimum(), decimals),
                        km(first.optimum().total(), decimals));
                problems++;
            }
            if (totalDistance(houses, o.from()) != o.total()
                    || totalDistance(houses, o.to()) != o.total()) {
                System.err.printf("FAIL: %s claims %s from %s, but the distances do not add up to it.%n",
                        r.label(), km(o.total(), decimals), spot(o, decimals));
                problems++;
            }
            boolean climbsLeft = o.from() == 0 || totalDistance(houses, o.from() - 1L) > o.total();
            boolean climbsRight = totalDistance(houses, o.to() + 1L) > o.total();
            if (!climbsLeft || !climbsRight) {
                System.err.printf("FAIL: %s is not the bottom: a metre further out is no worse.%n",
                        r.label());
                problems++;
            }
        }
        long optimum = first.optimum().total();
        for (Middle m : middles) {
            if (m.scaledTotal(houses) < m.denominator() * optimum) {
                System.err.printf("FAIL: the %s beats the supposed optimum of %s.%n",
                        m.label(), km(optimum, decimals));
                problems++;
            }
        }
        for (int q = 0; q < QUESTIONS.size(); q++) {
            double own = middles.get(q).scores(houses)[q];
            for (Middle other : middles) {
                if (!atMost(own, other.scores(houses)[q])) {
                    System.err.printf("FAIL: the %s should have the lowest %s, but the %s beats it.%n",
                            middles.get(q).label(), QUESTIONS.get(q), other.label());
                    problems++;
                }
            }
        }
        return problems == 0 ? 0 : 1;
    }

    /** The answer, in words, and what the other middles would cost. */
    private static void summarise(
            List<Result> results, List<Middle> middles, List<House> street, int[] houses, int decimals) {

        Optimum best = results.get(0).optimum();
        String total = km(best.total(), decimals);
        if (best.isPoint()) {
            String who = residents(street, best.from());
            System.err.printf("move in at %s, where %s %s: %s to %s.%n",
                    km(best.from(), decimals), who, who.contains(" and ") ? "live" : "lives",
                    total, everyone(houses.length));
        } else {
            System.err.printf("move in anywhere from %s (%s) to %s (%s): %s to %s, "
                            + "wherever you pick.%n",
                    km(best.from(), decimals), residents(street, best.from()),
                    km(best.to(), decimals), residents(street, best.to()),
                    total, everyone(houses.length));
        }

        System.err.println(results.size() > 1
                ? results.size() + " independent methods agree on it."
                : "only the median could run at this size; it is certified by the metre either side.");

        List<String> costs = new ArrayList<>();
        for (Middle m : middles.subList(1, middles.size())) {
            long scaled = m.scaledTotal(houses);
            long optimum = m.denominator() * best.total();
            String where = String.format(Locale.ROOT, "the %s, at %,.3f km, ",
                    m.label(), m.metres() / METRES_PER_KM);
            if (scaled == optimum) {
                costs.add(where + "is just as good");
            } else {
                double extra = (double) (scaled - optimum) / m.denominator() / METRES_PER_KM;
                costs.add(where + String.format(Locale.ROOT, "would cost %,.3f km more (%s)",
                        extra, percent((double) (scaled - optimum) / optimum)));
            }
        }
        System.err.println(String.join("; ", costs) + ".");
    }

    /** Run one method, timing it. */
    private static Result time(String label, Supplier<Optimum> method) {
        long started = System.nanoTime();
        Optimum optimum = method.get();
        return new Result(label, optimum, System.nanoTime() - started);
    }
}
