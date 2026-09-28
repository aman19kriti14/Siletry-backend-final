package com.siletry.messaging;

import com.siletry.auth.AppUser;
import com.siletry.auth.AppUserRepository;
import com.siletry.auth.CurrentUser;
import com.siletry.clinic.Clinic;
import com.siletry.clinic.ClinicRepository;
import com.siletry.common.ApiException;
import com.siletry.common.Phone;
import com.siletry.messaging.OutboundMessageService.Outgoing;
import com.siletry.patient.Patient;
import com.siletry.patient.PatientRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class InboxService {

    public record ConversationRow(Long id, Long patientId, String name, String initials, String phone, String preview,
                                  LocalDateTime lastMessageAt, ConversationState state, boolean emergency,
                                  int unreadCount, String handoffReason, String language, boolean takenOver) {}

    public record InboxList(long needsYou, long all, List<ConversationRow> items) {}

    public record MessageView(Long id, Message.Direction direction, Message.Sender sender, String body,
                              List<String> buttons, Message.Kind kind, Message.Status status, String error,
                              LocalDateTime createdAt, String sentByName, String templateName) {}

    public record ChatThread(ConversationRow conversation, boolean windowOpen, LocalDateTime windowClosesAt,
                         String takenOverByName, String botStep, List<MessageView> messages) {}

    private final ConversationRepository conversations;
    private final MessageRepository messages;
    private final PatientRepository patients;
    private final ClinicRepository clinics;
    private final AppUserRepository users;
    private final OutboundMessageService outbound;

    public InboxService(ConversationRepository conversations, MessageRepository messages, PatientRepository patients,
                        ClinicRepository clinics, AppUserRepository users, OutboundMessageService outbound) {
        this.conversations = conversations;
        this.messages = messages;
        this.patients = patients;
        this.clinics = clinics;
        this.users = users;
        this.outbound = outbound;
    }

    @Transactional(readOnly = true)
    public InboxList list(String tab, int limit) {
        Long clinicId = CurrentUser.clinicId();
        List<Conversation> list = "all".equalsIgnoreCase(tab)
                ? conversations.findRecent(clinicId, PageRequest.of(0, limit))
                : conversations.findByStates(clinicId, EnumSet.of(ConversationState.NEEDS_YOU), PageRequest.of(0, limit));
        return new InboxList(conversations.countByClinicIdAndState(clinicId, ConversationState.NEEDS_YOU),
                conversations.countByClinicIdAndLastMessageAtIsNotNull(clinicId), rows(list));
    }

    /** The "Needs a human" card on the overview. */
    @Transactional(readOnly = true)
    public List<ConversationRow> needsHuman(Long clinicId, int limit) {
        return rows(conversations.findByStates(clinicId, EnumSet.of(ConversationState.NEEDS_YOU), PageRequest.of(0, limit)));
    }

    @Transactional
    public ChatThread thread(Long id, int limit) {
        Conversation c = get(id);
        c.setUnreadCount(0);
        List<Message> latest = new ArrayList<>(messages.findLatest(c.getId(), PageRequest.of(0, Math.min(limit, 500))));
        Collections.reverse(latest);
        return thread(c, latest);
    }

    /** Messages newer than afterId (for polling). */
    @Transactional(readOnly = true)
    public List<MessageView> after(Long id, Long afterId) {
        Conversation c = get(id);
        return views(messages.findAfter(c.getId(), afterId == null ? 0L : afterId));
    }

    @Transactional
    public ChatThread takeOver(Long id) {
        Conversation c = get(id);
        c.setTakenOverByUserId(CurrentUser.userId());
        c.setTakenOverAt(LocalDateTime.now());
        c.setBotStep(null);
        c.setBotContext(null);
        return thread(id, 100);
    }

    @Transactional
    public ChatThread handBack(Long id) {
        Conversation c = get(id);
        c.setTakenOverByUserId(null);
        c.setTakenOverAt(null);
        c.setState(ConversationState.HANDLED);
        return thread(id, 100);
    }

    @Transactional
    public MessageView reply(Long id, String text) {
        if (text == null || text.isBlank()) throw ApiException.badRequest("Message is empty");
        Conversation c = get(id);
        if (!c.isTakenOver()) {
            c.setTakenOverByUserId(CurrentUser.userId());
            c.setTakenOverAt(LocalDateTime.now());
            c.setBotStep(null);
            c.setBotContext(null);
        }
        Clinic clinic = clinics.findById(c.getClinicId()).orElseThrow();
        Patient patient = patients.findById(c.getPatientId()).orElseThrow();
        Message m = outbound.send(clinic, patient, Outgoing.staff(text.trim(), CurrentUser.userId()));
        if (m.getStatus() == Message.Status.FAILED) throw ApiException.badRequest(m.getError());
        c.setState(ConversationState.YOU_REPLIED);
        c.setUnreadCount(0);
        return views(List.of(m)).get(0);
    }

    @Transactional
    public ChatThread resolve(Long id) {
        Conversation c = get(id);
        c.setState(ConversationState.HANDLED);
        c.setEmergency(false);
        c.setUnreadCount(0);
        return thread(id, 100);
    }

    @Transactional(readOnly = true)
    public Optional<Long> conversationIdForPatient(Long patientId) {
        return conversations.findByClinicIdAndPatientId(CurrentUser.clinicId(), patientId).map(Conversation::getId);
    }

    private Conversation get(Long id) {
        return conversations.findByIdAndClinicId(id, CurrentUser.clinicId()).orElseThrow(() -> ApiException.notFound("Conversation"));
    }

    private ChatThread thread(Conversation c, List<Message> list) {
        String takenBy = c.getTakenOverByUserId() == null ? null
                : users.findById(c.getTakenOverByUserId()).map(AppUser::getName).orElse(null);
        LocalDateTime closes = c.getLastInboundAt() == null ? null : c.getLastInboundAt().plusHours(24);
        return new ChatThread(rows(List.of(c)).get(0), c.isWindowOpen(), closes, takenBy, c.getBotStep(), views(list));
    }

    private List<ConversationRow> rows(List<Conversation> list) {
        if (list.isEmpty()) return List.of();
        Map<Long, Patient> pmap = patients.findAllById(list.stream().map(Conversation::getPatientId).toList())
                .stream().collect(Collectors.toMap(Patient::getId, Function.identity()));
        return list.stream().map(c -> {
            Patient p = pmap.get(c.getPatientId());
            return new ConversationRow(c.getId(), c.getPatientId(), p == null ? null : p.getName(),
                    p == null ? null : p.initials(), p == null ? null : Phone.pretty(p.getPhone()),
                    c.getLastMessagePreview(), c.getLastMessageAt(), c.getState(), c.isEmergency(),
                    c.getUnreadCount(), c.getHandoffReason(), c.getLanguage(), c.isTakenOver());
        }).toList();
    }

    private List<MessageView> views(List<Message> list) {
        Set<Long> userIds = list.stream().map(Message::getSentByUserId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> names = users.findAllById(userIds).stream().collect(Collectors.toMap(AppUser::getId, AppUser::getName));
        return list.stream().map(m -> new MessageView(m.getId(), m.getDirection(), m.getSender(), m.getBody(),
                m.getButtons() == null ? List.of() : Arrays.asList(m.getButtons().split("\n")),
                m.getKind(), m.getStatus(), m.getError(), m.getCreatedAt(),
                m.getSentByUserId() == null ? null : names.get(m.getSentByUserId()), m.getTemplateName())).toList();
    }
}
