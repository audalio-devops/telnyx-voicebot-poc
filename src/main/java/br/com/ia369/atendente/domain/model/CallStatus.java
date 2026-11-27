package br.com.ia369.atendente.domain.model;

/**
 * Representa o ciclo de vida de uma chamada no serviço Atendente Virtual.
 */
public sealed interface CallStatus permits
        CallStatus.Queued,
        CallStatus.Ringing,
        CallStatus.InProgress,
        CallStatus.Ended,
        CallStatus.Collecting,
        CallStatus.Completed,
        CallStatus.Partial,
        CallStatus.NoAnswer,
        CallStatus.Busy,
        CallStatus.Failed,
        CallStatus.Canceled {

    String code();

    default boolean isFinalPackageReady() {
        return this instanceof Completed || this instanceof Partial;
    }

    default boolean isActiveInTelnyx() {
        return this instanceof Queued || this instanceof Ringing || this instanceof InProgress;
    }

    default boolean requiresCollection() {
        return this instanceof Ended
                || this instanceof Collecting
                || this instanceof NoAnswer
                || this instanceof Busy
                || this instanceof Failed
                || this instanceof Canceled;
    }

    static CallStatus fromCode(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Status code cannot be blank");
        }
        return switch (code.trim().toUpperCase()) {
            case "QUEUED" -> Queued.INSTANCE;
            case "RINGING" -> Ringing.INSTANCE;
            case "IN_PROGRESS" -> InProgress.INSTANCE;
            case "ENDED" -> Ended.INSTANCE;
            case "COLLECTING" -> Collecting.INSTANCE;
            case "COMPLETED" -> Completed.INSTANCE;
            case "PARTIAL" -> Partial.INSTANCE;
            case "NO_ANSWER" -> NoAnswer.INSTANCE;
            case "BUSY" -> Busy.INSTANCE;
            case "FAILED" -> Failed.INSTANCE;
            case "CANCELED" -> Canceled.INSTANCE;
            default -> throw new IllegalArgumentException("Unknown CallStatus code: " + code);
        };
    }

    static CallStatus fromTelnyxStatus(String telnyxStatus) {
        if (telnyxStatus == null || telnyxStatus.isBlank()) {
            return Queued.INSTANCE;
        }
        return switch (telnyxStatus.trim().toLowerCase()) {
            case "queued", "initiated" -> Queued.INSTANCE;
            case "ringing" -> Ringing.INSTANCE;
            case "answered", "in-progress", "in_progress" -> InProgress.INSTANCE;
            case "completed" -> Ended.INSTANCE;
            case "no-answer", "no_answer" -> NoAnswer.INSTANCE;
            case "busy" -> Busy.INSTANCE;
            case "canceled", "cancelled" -> Canceled.INSTANCE;
            case "failed" -> Failed.INSTANCE;
            default -> Queued.INSTANCE;
        };
    }

    record Queued() implements CallStatus {
        public static final Queued INSTANCE = new Queued();
        @Override public String code() { return "QUEUED"; }
    }

    record Ringing() implements CallStatus {
        public static final Ringing INSTANCE = new Ringing();
        @Override public String code() { return "RINGING"; }
    }

    record InProgress() implements CallStatus {
        public static final InProgress INSTANCE = new InProgress();
        @Override public String code() { return "IN_PROGRESS"; }
    }

    record Ended() implements CallStatus {
        public static final Ended INSTANCE = new Ended();
        @Override public String code() { return "ENDED"; }
    }

    record Collecting() implements CallStatus {
        public static final Collecting INSTANCE = new Collecting();
        @Override public String code() { return "COLLECTING"; }
    }

    record Completed() implements CallStatus {
        public static final Completed INSTANCE = new Completed();
        @Override public String code() { return "COMPLETED"; }
    }

    record Partial() implements CallStatus {
        public static final Partial INSTANCE = new Partial();
        @Override public String code() { return "PARTIAL"; }
    }

    record NoAnswer() implements CallStatus {
        public static final NoAnswer INSTANCE = new NoAnswer();
        @Override public String code() { return "NO_ANSWER"; }
    }

    record Busy() implements CallStatus {
        public static final Busy INSTANCE = new Busy();
        @Override public String code() { return "BUSY"; }
    }

    record Failed() implements CallStatus {
        public static final Failed INSTANCE = new Failed();
        @Override public String code() { return "FAILED"; }
    }

    record Canceled() implements CallStatus {
        public static final Canceled INSTANCE = new Canceled();
        @Override public String code() { return "CANCELED"; }
    }
}

