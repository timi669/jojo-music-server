package cn.edu.seig.vibemusic.service.impl;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmailServiceImplTest {

    @Mock
    private JavaMailSenderImpl mailSender;

    @InjectMocks
    private EmailServiceImpl emailService;

    @BeforeEach
    void setFromAddress() {
        ReflectionTestUtils.setField(emailService, "from", "music@example.com");
    }

    @Test
    void sendVerificationCodeEmailReturnsNullWhenSmtpHostIsNotConfigured() {
        when(mailSender.getHost()).thenReturn("");

        assertNull(emailService.sendVerificationCodeEmail("user@example.com"));

        verify(mailSender, never()).createMimeMessage();
    }

    @Test
    void sendEmailReturnsFalseWhenSmtpProviderFails() {
        when(mailSender.getHost()).thenReturn("smtp.example.com");
        when(mailSender.getPassword()).thenReturn("configured-app-password");
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        when(mailSender.createMimeMessage()).thenReturn(message);
        doThrow(new MailSendException("SMTP unavailable")).when(mailSender).send(message);

        assertFalse(emailService.sendEmail("user@example.com", "subject", "content"));
    }
}
