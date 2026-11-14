package com.communicatorone.people;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat;
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber;
import java.util.Locale;
import java.util.Objects;

public record ContactIdentity(IdentityKind kind, String value) {

    private static final String UNKNOWN_REGION = "ZZ";

    public ContactIdentity {
        Objects.requireNonNull(kind, "kind");
        value = normalized(kind, value);
    }

    public static ContactIdentity phone(String raw) {
        return new ContactIdentity(IdentityKind.PHONE, raw);
    }

    public static ContactIdentity email(String raw) {
        return new ContactIdentity(IdentityKind.EMAIL, raw);
    }

    @Override
    public String toString() {
        return "ContactIdentity[" + kind + "]";
    }

    private static String normalized(IdentityKind kind, String raw) {
        if (raw == null || raw.isBlank()) {
            throw new InvalidContactIdentityException(kind);
        }
        var stripped = raw.strip();
        return switch (kind) {
            case PHONE -> e164(stripped);
            case EMAIL -> lowercaseEmail(stripped);
            case INSTAGRAM_USER, MESSENGER_USER, TELEGRAM_USER -> stripped;
        };
    }

    private static String e164(String phone) {
        var phoneNumbers = PhoneNumberUtil.getInstance();
        var number = parsed(phoneNumbers, phone);
        if (!phoneNumbers.isPossibleNumber(number)) {
            throw new InvalidContactIdentityException(IdentityKind.PHONE);
        }
        return phoneNumbers.format(number, PhoneNumberFormat.E164);
    }

    private static PhoneNumber parsed(PhoneNumberUtil phoneNumbers, String phone) {
        try {
            return phoneNumbers.parse(phone, UNKNOWN_REGION);
        } catch (NumberParseException unparseable) {
            throw new InvalidContactIdentityException(IdentityKind.PHONE);
        }
    }

    private static String lowercaseEmail(String email) {
        if (!email.contains("@")) {
            throw new InvalidContactIdentityException(IdentityKind.EMAIL);
        }
        return email.toLowerCase(Locale.ROOT);
    }
}
