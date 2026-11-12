package wherevercode.agent.core.session

import java.util.UUID
import wherevercode.agent.core.security.SecretRedactor

/**
 * SessionTypes (M2 S1): every value type of the unified session centre
 * plus the pure classification rules. No I/O, no Room, no model calls.
 *
 * WACF §5.1 minimal protocol fields (all ten land on [SessionMessage]):
 *  1. messageId — String (UUID), globally unique, dedup + traceability.
 *  2. sender / recipient — agentId/deviceId, direct addressing.
 *  3. conversationId / taskId / attemptId — ownership + traceability
 *     (U9: a session row also carries goalId/attemptId, see [SessionRecord]).
 *  4. messageType — CHAT / CONTROL / TASK / EVENT / DELIVERY.
 *  5. causationId — parent messageId (reply / derivation chain).
 *  6. idempotencyKey / operationId — client-generated stable id (U2);
 *     the same id retried executes once.
 *  7. deliveryState — PERSISTED / DELIVERED / CLAIMED / READ (U8).
 *  8. authVersion (+ goalVersion) — epoch + goalVersion snapshot taken
 *     at send time; stale-version writes are refused with the same
 *     counters ExecutionFence uses.
 *  9. ttl — seconds; an expired message is refused, never delivered.
 * 10. audit — metadata only (actor/action/ts/ok/errorCode), never the
 *     message body (M16 rule, same as AuditLog).
 *
 * Limits + cleaning reuse the agent_channel idea (80 / 4000 / 100 / 500):
 * session name 80 chars, message 4000 chars, 100 sessions per user,
 * 500 members per session. Secret-shaped content is NOT refused: it is
 * passed through the existing [SecretRedactor] before storage.
 */

/** What kind of session this is. */
enum class SessionKind {
    /** One-to-one private chat. */
    PRIVATE,

    /** Multi-member group chat. */
    GROUP,

    /** Task-bound discussion: exactly one taskId, never rebound (U9). */
    TASK,
}

/** WACF messageType whitelist (§5.1 field 4). */
enum class MessageKind {
    CHAT,
    CONTROL,
    TASK,
    EVENT,
    DELIVERY,
}

/** Delivery / read states (U8). Four states, never conflated:
 * PERSISTED (stored) is not DELIVERED, DELIVERED is not CLAIMED,
 * CLAIMED is not READ, and "sent" never pretends to be "executed". */
enum class DeliveryState {
    PERSISTED,
    DELIVERED,
    CLAIMED,
    READ,
}

/** Upper bounds (agent_channel: 80 / 4000 / 100 / 500). */
const val MAX_SESSION_NAME_CHARS: Int = 80
const val MAX_MESSAGE_CHARS: Int = 4000
const val MAX_SESSIONS_PER_USER: Int = 100
const val MAX_MEMBERS_PER_SESSION: Int = 500

/** Sender ids that may never be claimed by an ordinary member.
 * "owner" is a reserved word: nobody sends as "owner"; the session
 * owner is identified by the session row's ownerId instead. */
val RESERVED_SENDER_IDS: Set<String> = setOf("owner")

/** Who sent a message. */
data class SenderIdentity(
    val agentId: String,
    val deviceId: String? = null,
)

/** Audit metadata carried on a message (§5.1 field 10). Metadata only. */
data class MessageAuditMeta(
    val actor: String,
    val action: String,
    val epochSeconds: Long,
    val ok: Boolean,
    val errorCode: String? = null,
)

/** One session (conversation) row, as a value type. */
data class SessionRecord(
    val sessionId: String,
    val name: String,
    val kind: SessionKind,
    val ownerId: String,
    /** Member agent ids, owner included. */
    val members: List<String>,
    /** Task-bound discussion only; once set it is never rebound (U9). */
    val taskId: String? = null,
    /** Traceable to the goal / attempt the discussion belongs to (U9). */
    val goalId: String? = null,
    val attemptId: String? = null,
    val createdAtEpochSeconds: Long,
    val updatedAtEpochSeconds: Long,
)

/** One session message: all ten WACF §5.1 fields live here. */
data class SessionMessage(
    /** 1. Globally unique id (UUID). */
    val messageId: String,
    /** 3. Owning conversation. */
    val conversationId: String,
    /** 2. Direct addressing: sender always set, recipient null = all members. */
    val senderId: String,
    val recipientId: String? = null,
    /** 3. Task / attempt this message belongs to (nullable). */
    val taskId: String? = null,
    val attemptId: String? = null,
    /** 4. Message kind (S3 classification is authoritative). */
    val messageType: MessageKind,
    /** 5. Parent messageId when this is a reply / derivation. */
    val causationId: String? = null,
    /** 6. Client-generated stable id; defaults to [messageId]. */
    val idempotencyKey: String,
    /** 6. Control operation id (U2); null for non-control messages. */
    val operationId: String? = null,
    /** 7. Delivery state of the message itself (per-member states live
     * in the deliveries table, see DeliveryTracker). */
    val deliveryState: DeliveryState = DeliveryState.PERSISTED,
    /** 8. authEpoch snapshot at send time. */
    val authVersion: Long = 1,
    /** 8. goalVersion snapshot at send time. */
    val goalVersion: Long = 1,
    /** 9. Time-to-live in seconds; null = no expiry. */
    val ttlSeconds: Long? = null,
    /** Body, already cleaned and secret-redacted by the ledger. */
    val content: String,
    val createdAtEpochSeconds: Long,
    /** 10. Audit metadata (never the body). */
    val audit: MessageAuditMeta? = null,
) {
    /** True once [nowEpochSeconds] passes createdAt + ttl. */
    fun isExpired(nowEpochSeconds: Long): Boolean =
        ttlSeconds != null && nowEpochSeconds > createdAtEpochSeconds + ttlSeconds

    companion object {
        fun newId(): String = UUID.randomUUID().toString()

        fun isValidMessageId(id: String): Boolean =
            try {
                UUID.fromString(id)
                true
            } catch (_: IllegalArgumentException) {
                false
            }
    }
}

/** Build a fresh message with a generated UUID id. Callers pass the
 * already-cleaned, already-redacted content (the ledger does that). */
fun newSessionMessage(
    conversationId: String,
    senderId: String,
    content: String,
    recipientId: String? = null,
    taskId: String? = null,
    attemptId: String? = null,
    messageType: MessageKind = MessageKind.CHAT,
    causationId: String? = null,
    messageId: String? = null,
    idempotencyKey: String? = null,
    operationId: String? = null,
    authVersion: Long = 1,
    goalVersion: Long = 1,
    ttlSeconds: Long? = null,
    createdAtEpochSeconds: Long = System.currentTimeMillis() / 1000,
): SessionMessage {
    val id = messageId ?: SessionMessage.newId()
    return SessionMessage(
        messageId = id,
        conversationId = conversationId,
        senderId = senderId,
        recipientId = recipientId,
        taskId = taskId,
        attemptId = attemptId,
        messageType = messageType,
        causationId = causationId,
        idempotencyKey = idempotencyKey ?: id,
        operationId = operationId,
        authVersion = authVersion,
        goalVersion = goalVersion,
        ttlSeconds = ttlSeconds,
        content = content,
        createdAtEpochSeconds = createdAtEpochSeconds,
    )
}

/** Cleaning, ported from the agent_channel `_clean_*` idea: trim,
 * strip control characters (except newline/tab), collapse 3+ blank
 * lines. Never invents content. */
fun cleanText(text: String): String {
    val stripped = text.filter { ch -> ch == '\n' || ch == '\t' || !ch.isISOControl() }
    return stripped.trim().replace(Regex("\n{3,}"), "\n\n")
}

/** Session-name validation: non-blank, at most 80 chars. */
fun validateSessionName(name: String): String {
    val cleaned = cleanText(name)
    require(cleaned.isNotEmpty()) { "session name must not be empty" }
    require(cleaned.length <= MAX_SESSION_NAME_CHARS) {
        "session name exceeds $MAX_SESSION_NAME_CHARS characters"
    }
    return cleaned
}

/** Message-content validation: non-blank, at most 4000 chars. */
fun validateMessageContent(content: String): String {
    val cleaned = cleanText(content)
    require(cleaned.isNotEmpty()) { "message must not be empty" }
    require(cleaned.length <= MAX_MESSAGE_CHARS) {
        "message exceeds $MAX_MESSAGE_CHARS characters"
    }
    return cleaned
}

/** Per-user session quota: the 101st session is refused. */
fun requireSessionSlot(existingCount: Int) {
    require(existingCount < MAX_SESSIONS_PER_USER) {
        "too many sessions for this user (max $MAX_SESSIONS_PER_USER)"
    }
}

/** Member-list validation: non-empty, at most 500 members. */
fun validateMembers(members: List<String>): List<String> {
    require(members.isNotEmpty()) { "session must have at least one member" }
    val distinct = members.filter { it.isNotBlank() }.distinct()
    require(distinct.size <= MAX_MEMBERS_PER_SESSION) {
        "session exceeds $MAX_MEMBERS_PER_SESSION members"
    }
    return distinct
}

/** Sender validation: non-blank, not a reserved word, must be a
 * session member. */
fun validateSender(senderId: String, members: List<String>) {
    require(senderId.isNotBlank()) { "sender must not be empty" }
    require(senderId !in RESERVED_SENDER_IDS) { "sender id is reserved: $senderId" }
    require(senderId in members) { "sender is not a member of this session" }
}

/** reply_to validation: a parent must exist and belong to the same
 * conversation. [parentMessage] is the looked-up row (null = missing). */
fun validateReplyTo(
    causationId: String?,
    parentMessage: SessionMessage?,
    conversationId: String,
) {
    if (causationId == null) return
    require(parentMessage != null) { "reply target does not exist: $causationId" }
    require(parentMessage.conversationId == conversationId) {
        "reply target belongs to another conversation"
    }
}

/** Secret-shaped content is redacted with the existing SecretRedactor,
 * never refused and never stored verbatim. */
fun redactMessageContent(content: String): String =
    SecretRedactor.redact(content, MAX_MESSAGE_CHARS)

/** True when the text contains anything shaped like a secret. */
fun messageContainsSecret(content: String): Boolean =
    SecretRedactor.containsSecret(content)

// ---------------------------------------------------------------------------
// Control-command classification (deterministic; zero model calls).
//
// Grammar (the ONLY shape that becomes CONTROL):
//   <verb> <taskId> <原因|理由|reason>[:：] <reason>
// with <verb> one of the six first-batch verbs below (U10), <taskId> an
// ASCII id token, and a non-blank reason. Everything else is CHAT:
// missing reason/taskId -> CHAT; free text ("帮我暂停一下想想") -> CHAT.
// A command-shaped line whose verb is outside the whitelist is REJECTED
// explicitly (never silently treated as chat); 换人 (person swap) is
// rejected as its own explicit error (U10).
// ---------------------------------------------------------------------------

/** The six first-batch control verbs (U10). */
enum class ControlVerb {
    PAUSE,
    RESUME,
    STOP,
    CHANGE_GOAL,
    REVOKE,
    TAKEOVER,
}

/** Verb tokens (Chinese + English) accepted for each verb. */
val CONTROL_VERB_TOKENS: Map<String, ControlVerb> = mapOf(
    "暂停" to ControlVerb.PAUSE,
    "pause" to ControlVerb.PAUSE,
    "恢复" to ControlVerb.RESUME,
    "resume" to ControlVerb.RESUME,
    "停止" to ControlVerb.STOP,
    "stop" to ControlVerb.STOP,
    "改目标" to ControlVerb.CHANGE_GOAL,
    "retarget" to ControlVerb.CHANGE_GOAL,
    "change_goal" to ControlVerb.CHANGE_GOAL,
    "change-goal" to ControlVerb.CHANGE_GOAL,
    "撤权" to ControlVerb.REVOKE,
    "revoke" to ControlVerb.REVOKE,
    "接管" to ControlVerb.TAKEOVER,
    "takeover" to ControlVerb.TAKEOVER,
)

/** Person-swap tokens: a later explicit action, refused here (U10). */
val PERSON_SWAP_TOKENS: Set<String> = setOf("换人", "handover", "reassign", "交接")

/** One parsed control instruction. */
data class ControlInstruction(
    val verb: ControlVerb,
    val taskId: String,
    val reason: String,
    /** The verbatim command text, for audit metadata (never the body). */
    val rawText: String,
)

/** Result of the shape-only parse (no authorization involved). */
sealed interface CommandParse {
    /** Not a command shape at all: ordinary chat. */
    object NotACommand : CommandParse

    /** A well-formed six-verb command. */
    data class Command(val instruction: ControlInstruction) : CommandParse

    /** Command-shaped but refused: unknown verb or person swap. */
    data class Rejected(val errorCode: String, val reason: String) : CommandParse
}

private val COMMAND_PATTERN: Regex =
    Regex("""^(\S+)\s+([A-Za-z0-9][A-Za-z0-9_\-]{0,63})\s+(?:原因|理由|reason)[:：]\s*(.+)$""")

private val VERB_WITHOUT_REASON_PATTERN: Regex =
    Regex("""^(\S+)\s+([A-Za-z0-9][A-Za-z0-9_\-]{0,63})\s*$""")

/** Shape-only parse of a control command. Authorization is checked by
 * the caller ([classify] / the router), never here. */
fun parseControlCommand(text: String): CommandParse {
    val cleaned = cleanText(text)
    val match = COMMAND_PATTERN.matchEntire(cleaned) ?: return CommandParse.NotACommand
    val verbToken = match.groupValues[1]
    val taskId = match.groupValues[2]
    val reason = match.groupValues[3].trim()
    if (reason.isEmpty()) return CommandParse.NotACommand
    if (verbToken in PERSON_SWAP_TOKENS) {
        return CommandParse.Rejected(
            errorCode = "person_swap_not_supported",
            reason = "换人是后续的独立控制动作，首批不支持：请勿绕过门禁换人",
        )
    }
    val verb = CONTROL_VERB_TOKENS[verbToken.lowercase()]
        ?: CONTROL_VERB_TOKENS[verbToken]
        ?: return CommandParse.Rejected(
            errorCode = "unknown_control_verb",
            reason = "不在首批控制动词白名单内：$verbToken",
        )
    return CommandParse.Command(ControlInstruction(verb, taskId, reason, cleaned))
}

/** True when the text is "<whitelisted verb> <taskId>" with no reason
 * segment — used by the router to report "reason required" instead of
 * silently treating the line as chat. */
fun controlVerbWithoutReason(text: String): ControlVerb? {
    val cleaned = cleanText(text)
    val match = VERB_WITHOUT_REASON_PATTERN.matchEntire(cleaned) ?: return null
    val token = match.groupValues[1]
    return CONTROL_VERB_TOKENS[token.lowercase()] ?: CONTROL_VERB_TOKENS[token]
}

/** Classification result of one incoming message. */
sealed interface Classification {
    /** Ordinary chat: never touches the task ledger. */
    data class Chat(val reason: String) : Classification

    /** A control instruction from an authorized sender. */
    data class Control(val instruction: ControlInstruction) : Classification

    /** A control-shaped attempt that is refused (unknown verb, person
     * swap, or unauthorized sender). The message row is still kept. */
    data class Rejected(val errorCode: String, val reason: String) : Classification
}

/**
 * Pure classification: six verbs + taskId + reason + authorized sender
 * -> CONTROL; missing reason/taskId -> CHAT; free text never becomes
 * CONTROL. [senderAuthorized] is decided by the caller (ledger: session
 * owner or device grant; the router re-checks with the delegation
 * switch). Expired-TTL and limit checks live in the ledger, not here.
 */
fun classify(text: String, senderAuthorized: Boolean): Classification {
    return when (val parsed = parseControlCommand(text)) {
        is CommandParse.NotACommand -> Classification.Chat("free_text_or_incomplete_command")
        is CommandParse.Rejected ->
            Classification.Rejected(parsed.errorCode, parsed.reason)
        is CommandParse.Command -> {
            if (!senderAuthorized) {
                Classification.Rejected(
                    errorCode = "sender_not_authorized",
                    reason = "发送者无权发出控制指令",
                )
            } else {
                Classification.Control(parsed.instruction)
            }
        }
    }
}
