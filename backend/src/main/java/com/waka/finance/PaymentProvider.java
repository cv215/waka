package com.waka.finance;

/**
 * A Mobile Money provider (R14). WAKA never trusts a notification body: a notification only says "look
 * again", and the status is read back from the provider with {@link #payment} or {@link #transfer}.
 */
public interface PaymentProvider {
    enum State { PENDING, SUCCEEDED, FAILED }
    /** What the provider says now; amount and currency are null when it did not say. */
    record Status(State state, Long amountXaf, String currency) { }
    /** The provider's reference, or null when the call failed before anything was created. */
    record Started(String reference) { }

    String name();

    /** Asks the payer to confirm {@code amountXaf} on their phone. {@code reference} is WAKA's payment id. */
    Started collect(String reference, long amountXaf, String channel, String phone, String description) throws ProviderUnavailable;
    Status payment(String providerReference) throws ProviderUnavailable;

    /** Sends {@code amountXaf} to a Mobile Money number. {@code reference} is WAKA's withdrawal id. */
    Started send(String reference, long amountXaf, String channel, String phone, String description) throws ProviderUnavailable;
    Status transfer(String reference) throws ProviderUnavailable;

    /** Whether a notification really comes from the provider; {@code header} reads a request header by name. */
    boolean authentic(byte[] body, java.util.function.UnaryOperator<String> header);

    /** The references a notification mentions; each is only a hint to look the status up again. */
    java.util.Set<String> references(byte[] body);

    /** The provider could not be reached or answered something unusable: the outcome is unknown. */
    final class ProviderUnavailable extends Exception {
        public ProviderUnavailable(String message) { super(message, null, false, false); }
    }
}
