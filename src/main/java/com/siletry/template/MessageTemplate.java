package com.siletry.template;

import com.siletry.common.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/**
 * WhatsApp templates the clinic uses outside the 24-hour window.
 * Status tracks Meta approval; the body uses {{1}}-style placeholders.
 */
@Entity
@Table(name = "message_template",
        uniqueConstraints = @UniqueConstraint(name = "uk_template_clinic_name_lang", columnNames = {"clinic_id", "name", "language"}))
@Getter
@Setter
public class MessageTemplate extends BaseEntity {

    public enum Category { UTILITY, MARKETING, AUTHENTICATION }

    public enum Status { DRAFT, SUBMITTED, APPROVED, REJECTED }

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Category category = Category.UTILITY;

    @Column(nullable = false, length = 10)
    private String language = "en";

    @Column(nullable = false, columnDefinition = "text")
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.DRAFT;

    @Column(name = "provider_template_id")
    private String providerTemplateId;

    @Column(name = "rejection_reason")
    private String rejectionReason;
}
