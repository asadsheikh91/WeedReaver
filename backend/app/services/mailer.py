"""Outgoing mail (invitations, password resets). Console in development, SMTP in production."""

from __future__ import annotations

import logging
import smtplib
from email.message import EmailMessage

from app.core.config import get_settings

log = logging.getLogger("weedreaver.mail")


def send(to: str, subject: str, body: str) -> bool:
    s = get_settings()
    if s.mail_backend == "console" or not s.smtp_host:
        log.info("Mail to %s: %s\n%s", to, subject, body)
        return False
    msg = EmailMessage()
    msg["From"] = s.smtp_from
    msg["To"] = to
    msg["Subject"] = subject
    msg.set_content(body)
    try:
        with smtplib.SMTP(s.smtp_host, s.smtp_port, timeout=15) as smtp:
            if s.smtp_starttls:
                smtp.starttls()
            if s.smtp_user:
                smtp.login(s.smtp_user, s.smtp_password or "")
            smtp.send_message(msg)
        return True
    except (OSError, smtplib.SMTPException):
        log.exception("Could not send mail to %s", to)
        return False
