package com.sujula.service.aftersales;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * Takes telephone numbers, addresses and links out of what people write to each
 * other.
 *
 * <p><strong>Why this exists at all.</strong> A seller who persuades a buyer to
 * pay by mobile money outside the platform has taken the buyer's protection away
 * with them: no escrow, so the money is gone the moment it is sent; no custody
 * chain, so nobody can prove whether anything arrived; no dispute, no refund, no
 * record. On this route the buyer is usually in Europe and the goods are going to
 * somebody else entirely, which makes them exactly the person least able to walk
 * into the shop and complain. The filter is not about controlling conversation —
 * it is about keeping the transaction inside the thing that can protect it.
 *
 * <p><strong>What it does not do.</strong> It does not block the message. A
 * message that vanished would teach people to write in code; a message that
 * arrives with the number taken out and a line saying so teaches them the rule.
 * Both halves are kept — {@code ThreadMessage} carries the original — so a
 * buyer whose innocent sentence was mangled can have somebody look, and a seller
 * who tries this every week leaves a pattern.
 *
 * <p><strong>What it deliberately leaves alone.</strong> Money, dates, order
 * numbers, tracking codes, IMEIs and quantities. Over-redaction is not the safe
 * side here: "I paid 12,000 GMD on the 3rd" turning into "I paid ▮ on the ▮" is a
 * message that cannot be acted on, in a conversation that exists to sort out an
 * order — and the people it fails first are the ones writing in their second
 * language about a parcel that has not arrived.
 */
@Component
public class ContactDetailFilter {

    /** What replaces what was taken out. Visible, because a silent edit is a lie. */
    public static final String REDACTION = "[removed]";

    /**
     * Electronic mail addresses.
     *
     * <p>Also catches the obvious dodges — "name at gmail dot com" — because
     * they are common enough here to be the normal way of writing one rather
     * than a clever evasion.
     */
    private static final Pattern EMAIL = Pattern.compile(
            "[A-Za-z0-9._%+-]+\\s*(?:@|\\(at\\)|\\[at\\]|\\s+at\\s+)\\s*"
                    + "[A-Za-z0-9.-]+\\s*(?:\\.|\\s+dot\\s+)\\s*[A-Za-z]{2,}",
            Pattern.CASE_INSENSITIVE);

    /** Anything that looks like a link, with or without the scheme. */
    private static final Pattern URL = Pattern.compile(
            "\\b(?:https?://|www\\.)\\S+"
                    + "|\\b[A-Za-z0-9-]+\\.(?:com|net|org|io|gm|sn|es|co|me|app|shop|store|link)"
                    + "(?:/\\S*)?\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * A handle on somebody else's network.
     *
     * <p>Named rather than guessed. "@fatou" on its own is how people address
     * each other and is not an attempt to leave the platform; "whatsapp me on
     * @fatou" is. So the named services carry the match, and a bare handle does
     * not.
     */
    private static final Pattern SOCIAL = Pattern.compile(
            "\\b(?:whats\\s?app|whatsap|wa\\.me|telegram|signal|viber|imo|messenger|instagram|"
                    + "snapchat|tiktok|facebook|fb)\\b[^.!?\\n]{0,40}",
            Pattern.CASE_INSENSITIVE);

    /**
     * A telephone number.
     *
     * <p>Seven digits or more once separators are discounted, because that is
     * what a number is here and anything shorter is a quantity or a price. The
     * separators allowed are the ones people actually type: spaces, hyphens,
     * dots, brackets and a leading plus.
     *
     * <p>Bounded to avoid eating the sentence around it, and checked afterwards
     * against the exclusions below — an order number and a telephone number look
     * the same to a regular expression and completely different to a person.
     */
    private static final Pattern PHONE = Pattern.compile(
            "(?<![\\w.])\\+?\\d(?:[\\d\\s().-]{5,18})\\d(?![\\w.])");

    /**
     * Money, which must survive.
     *
     * <p>Checked before the phone pattern is allowed to claim a run of digits.
     * "12,000 GMD" and "€180.00" are the substance of most of these
     * conversations, and a filter that removed them would make the thread
     * useless for the thing it exists to do.
     */
    private static final Pattern MONEY = Pattern.compile(
            "(?:[€£$]\\s*)?\\d[\\d,. ]*\\s*"
                    + "(?:GMD|XOF|CFA|EUR|USD|GBP|SEK|NOK|DKK|dalasi|dalasis|euros?|pounds?|dollars?)"
                    + "\\b|[€£$]\\s*\\d[\\d,.]*",
            Pattern.CASE_INSENSITIVE);

    /** A date written as digits, which must also survive. */
    private static final Pattern DATE = Pattern.compile(
            "\\b\\d{1,4}[/.-]\\d{1,2}[/.-]\\d{1,4}\\b");

    /** What one pass over a message produced. */
    public record Result(String cleaned, Set<String> kinds) {

        public boolean changed() {
            return !kinds.isEmpty();
        }

        /** The kinds as they are stored on the message row. */
        public String kindsAsText() {
            return kinds.isEmpty() ? null : String.join(",", kinds);
        }

        /**
         * What the sender is told, in words that say what to do instead.
         *
         * <p>Naming the rule rather than scolding. Somebody who has just had
         * their message edited is entitled to know why, and "keep it here and
         * you are covered" is a reason rather than an instruction.
         */
        public String notice() {
            if (kinds.isEmpty()) {
                return null;
            }
            return "Some contact details were removed from your message. Keep the conversation "
                    + "here: if anything goes wrong with an order arranged on Sujula, the money is "
                    + "held until the parcel is proven delivered and there is a record to settle it "
                    + "with. Arranged privately, there is neither.";
        }
    }

    /** Runs the filter over a message. */
    public Result clean(String text) {
        if (text == null || text.isBlank()) {
            return new Result(text, Set.of());
        }

        Set<String> kinds = new LinkedHashSet<>();
        String working = text;

        working = replace(working, EMAIL, kinds, "EMAIL");
        working = replace(working, URL, kinds, "LINK");
        working = replace(working, SOCIAL, kinds, "SOCIAL");
        working = replacePhones(working, kinds);

        return new Result(working, kinds);
    }

    private static String replace(String text, Pattern pattern, Set<String> kinds, String kind) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            return text;
        }
        kinds.add(kind);
        return matcher.reset().replaceAll(Matcher.quoteReplacement(REDACTION));
    }

    /**
     * Numbers, with everything that is not a telephone number put back.
     *
     * <p>Done as its own pass rather than as a cleverer pattern, because the
     * question "is this run of digits a phone number" is not one a regular
     * expression can answer. The pattern finds candidates; this decides.
     */
    private static String replacePhones(String text, Set<String> kinds) {
        Matcher matcher = PHONE.matcher(text);
        StringBuilder out = new StringBuilder();
        boolean found = false;
        int last = 0;

        while (matcher.find()) {
            String candidate = matcher.group();
            if (isNotATelephoneNumber(text, matcher.start(), matcher.end(), candidate)) {
                continue;
            }
            out.append(text, last, matcher.start()).append(REDACTION);
            last = matcher.end();
            found = true;
        }
        if (!found) {
            return text;
        }
        kinds.add("PHONE");
        return out.append(text.substring(last)).toString();
    }

    /**
     * The exclusions, each one a thing people actually write.
     *
     * <p>Order numbers, tracking codes and IMEIs all reach this as long runs of
     * digits, and every one of them is exactly what somebody sorting out a
     * parcel needs to be able to send.
     */
    private static boolean isNotATelephoneNumber(String text, int start, int end, String candidate) {
        int digits = 0;
        for (int i = 0; i < candidate.length(); i++) {
            if (Character.isDigit(candidate.charAt(i))) {
                digits++;
            }
        }
        if (digits < 7) {
            return true;
        }

        // Money and dates keep their digits. Checked by overlap rather than by
        // re-matching the fragment, because "12,000 GMD" is only money when the
        // currency beside it is in view.
        if (overlaps(MONEY, text, start, end) || overlaps(DATE, text, start, end)) {
            return true;
        }

        // An IMEI is fifteen digits and a serial number is longer still. A
        // telephone number that long does not exist, and a buyer quoting the
        // handset they were sent is doing the right thing.
        if (digits >= 14) {
            return true;
        }

        // Attached to a reference: SJL-1403, PARC7K3M..., anything where the run
        // of digits is part of a larger token with letters in it. The pattern's
        // own boundaries catch most of this; this catches a prefix separated by
        // a hyphen, which is how every reference on this platform is written.
        int before = start - 1;
        while (before >= 0 && (text.charAt(before) == '-' || text.charAt(before) == '/')) {
            before--;
        }
        return before >= 0 && before < start - 1 && Character.isLetter(text.charAt(before));
    }

    private static boolean overlaps(Pattern pattern, String text, int start, int end) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            if (matcher.start() < end && start < matcher.end()) {
                return true;
            }
        }
        return false;
    }
}
