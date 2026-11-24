import nodemailer from 'nodemailer';
import type { OtpDeliveryInfo } from '../types/finance.ts';

export const ADMIN_GMAIL = 'shakti.chahar@gmail.com';

export function normalizeEmail(raw: string): string {
  return String(raw || '').trim().toLowerCase();
}

export function isValidEmail(email: string): boolean {
  return /^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/.test(email);
}

/**
 * Normalizes mobile numbers to E.164 format.
 * 10-digit Indian mobile numbers automatically receive the +91 prefix.
 */
export function normalizeMobile(raw: string): string {
  const cleaned = String(raw || '').trim().replace(/[\s\-()]/g, '');
  if (!cleaned) return '';
  if (/^\d{10}$/.test(cleaned)) {
    return `+91${cleaned}`;
  }
  if (/^91\d{10}$/.test(cleaned)) {
    return `+${cleaned}`;
  }
  if (/^\+\d{10,15}$/.test(cleaned)) {
    return cleaned;
  }
  return cleaned;
}

export function isValidMobile(mobile: string): boolean {
  const normalized = normalizeMobile(mobile);
  return /^\+\d{10,15}$/.test(normalized);
}

export function maskEmail(email?: string | null): string | null {
  const clean = normalizeEmail(email || '');
  if (!clean || !clean.includes('@')) return null;
  const [local, domain] = clean.split('@');
  if (local.length <= 2) {
    return `${local[0] || '*'}***@${domain}`;
  }
  return `${local.slice(0, 2)}${'*'.repeat(Math.min(5, Math.max(2, local.length - 2)))}@${domain}`;
}

export function maskMobile(mobile?: string | null): string | null {
  const clean = normalizeMobile(mobile || '');
  if (!clean || clean.length < 6) return null;
  const last4 = clean.slice(-4);
  const prefix = clean.startsWith('+91') ? '+91' : clean.slice(0, 3);
  return `${prefix} ••••••${last4}`;
}

function escapeHtml(value: string): string {
  return String(value || '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

async function sendEmailOtp(
  toEmail: string,
  fullName: string,
  loginIdentifier: string,
  otp: string,
  purpose: 'login' | 'direct-otp' | 'register' = 'login'
): Promise<boolean> {
  const gmailPass = process.env.GMAIL_APP_PASSWORD?.trim();
  const smtpPass = process.env.SMTP_PASS?.trim() || gmailPass;
  if (!smtpPass) {
    return false;
  }

  const host = process.env.SMTP_HOST?.trim() || 'smtp.gmail.com';
  const user = process.env.SMTP_USER?.trim() || ADMIN_GMAIL;
  const port = Number(process.env.SMTP_PORT || (host === 'smtp.gmail.com' ? 465 : 587));
  const secure =
    process.env.SMTP_SECURE !== undefined
      ? process.env.SMTP_SECURE === 'true'
      : port === 465;
  const from = process.env.SMTP_FROM?.trim() || `LedgerFlow Security <${user}>`;

  const transporter = nodemailer.createTransport({
    host,
    port,
    secure,
    auth: { user, pass: smtpPass },
    connectionTimeout: 10000,
    greetingTimeout: 10000,
  });

  const plainName = String(fullName || '').replace(/[\r\n<>]/g, '').trim();
  const plainLogin = String(loginIdentifier || '').replace(/[\r\n<>]/g, '').trim();
  const safeNameHtml = escapeHtml(plainName);
  const safeLoginHtml = escapeHtml(plainLogin);

  const isRegistration = purpose === 'register';
  const subject = isRegistration
    ? `Verify Your Email — Registration OTP (${otp})`
    : `LedgerFlow Login OTP (${otp}) — ${plainLogin}`;
  const heading = isRegistration ? 'Verify Your Registered Email' : 'LedgerFlow Login Verification';
  const bodyLead = isRegistration
    ? 'Use the one-time verification code below to verify your registered email address. Once verified, an administrator will review and approve your account before you can sign in:'
    : 'Use the one-time verification code below to complete sign-in:';
  const plainText = isRegistration
    ? `Hello ${plainName},\n\nThank you for creating an account (${plainLogin}).\n\nYour 6-digit email verification OTP is: ${otp}\n\nThis code expires in 5 minutes and can only be used once. After verifying your email, your account will await administrator approval before sign-in is enabled.`
    : `Hello ${plainName},\n\nA login verification OTP was requested for account: ${plainLogin}\n\nYour 6-digit LedgerFlow OTP is: ${otp}\n\nThis code expires in 5 minutes and can only be used once.`;

  await transporter.sendMail({
    from,
    to: toEmail,
    subject,
    text: plainText,
    html: `
      <div style="font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; max-width: 480px; margin: 0 auto; padding: 24px; border: 1px solid #e2e8f0; border-radius: 12px;">
        <h2 style="margin: 0 0 12px; color: #0f172a; font-size: 18px;">${heading}</h2>
        <p style="margin: 0 0 8px; color: #475569; font-size: 14px;">Account: <strong>${safeNameHtml}</strong> (${safeLoginHtml})</p>
        <p style="margin: 0 0 16px; color: #475569; font-size: 14px;">${bodyLead}</p>
        <div style="background: #eef2ff; border: 1px solid #c7d2fe; border-radius: 10px; padding: 16px; text-align: center; font-size: 28px; font-weight: 700; letter-spacing: 8px; color: #1d358f; font-family: monospace;">
          ${otp}
        </div>
        <p style="margin: 16px 0 0; color: #64748b; font-size: 12px;">This code is valid for 5 minutes and can only be used once.</p>
      </div>
    `,
  });

  return true;
}

async function sendSmsOtp(toMobile: string, otp: string): Promise<boolean> {
  const sid = process.env.TWILIO_ACCOUNT_SID?.trim();
  const token = process.env.TWILIO_AUTH_TOKEN?.trim();
  const from = process.env.TWILIO_FROM_NUMBER?.trim();
  if (!sid || !token || !from) {
    return false;
  }

  const endpoint = `https://api.twilio.com/2010-04-01/Accounts/${encodeURIComponent(sid)}/Messages.json`;
  const body = new URLSearchParams({
    To: toMobile,
    From: from,
    Body: `LedgerFlow OTP: ${otp}. Valid for 5 minutes. Do not share this code with anyone.`,
  });

  const authHeader = Buffer.from(`${sid}:${token}`).toString('base64');
  const res = await fetch(endpoint, {
    method: 'POST',
    headers: {
      Authorization: `Basic ${authHeader}`,
      'Content-Type': 'application/x-www-form-urlencoded',
    },
    body: body.toString(),
  });

  if (!res.ok) {
    const errText = await res.text().catch(() => '');
    throw new Error(`SMS gateway error (${res.status}): ${errText.slice(0, 120)}`);
  }

  return true;
}

/**
 * Dispatches OTP directly to the user's own registered email and/or registered mobile number:
 * - For new account registration (`purpose === 'register'`), sends Email OTP to the user's registered email.
 * - For login (`purpose === 'login' | 'direct-otp'`), sends Email OTP to the user's registered email and SMS OTP to the user's registered mobile (if present).
 */
export async function dispatchLoginOtp(params: {
  fullName: string;
  loginIdentifier: string;
  email?: string | null;
  mobile?: string | null;
  otp: string;
  purpose?: 'login' | 'direct-otp' | 'register';
}): Promise<OtpDeliveryInfo> {
  const purpose = params.purpose || 'login';
  const isRegistration = purpose === 'register';
  const cleanEmail = normalizeEmail(params.email || '');
  const hasEmail = isValidEmail(cleanEmail);
  const cleanMobile = normalizeMobile(params.mobile || '');
  const hasMobile = !isRegistration && isValidMobile(cleanMobile);

  const channels: Array<'email' | 'mobile'> = [];
  if (hasEmail) channels.push('email');
  if (hasMobile) channels.push('mobile');

  let emailSent = false;
  let smsSent = false;
  const failures: string[] = [];

  if (hasEmail) {
    try {
      emailSent = await sendEmailOtp(
        cleanEmail,
        params.fullName,
        params.loginIdentifier,
        params.otp,
        purpose
      );
    } catch (err) {
      console.error('OTP email delivery failed:', err instanceof Error ? err.message : err);
      failures.push('Email');
    }
  }

  if (hasMobile) {
    try {
      smsSent = await sendSmsOtp(cleanMobile, params.otp);
    } catch (err) {
      console.error('OTP SMS delivery failed:', err instanceof Error ? err.message : err);
      failures.push('SMS');
    }
  }

  const displayEmail = hasEmail ? maskEmail(cleanEmail) || cleanEmail : null;
  const displayMobile = hasMobile ? maskMobile(cleanMobile) || cleanMobile : null;

  if (emailSent || smsSent) {
    const deliveredParts: string[] = [];
    if (emailSent && displayEmail) {
      deliveredParts.push(`registered email (${displayEmail})`);
    }
    if (smsSent && displayMobile) {
      deliveredParts.push(`registered mobile (${displayMobile})`);
    }
    const notice =
      failures.length > 0
        ? `OTP sent to your ${deliveredParts.join(' and ')} (${failures.join('/')} gateway unavailable).`
        : `OTP sent to your ${deliveredParts.join(' and ')}.`;

    return {
      emailSent,
      smsSent,
      mode: 'live',
      channels,
      notice,
    };
  }

  const targetParts: string[] = [];
  if (displayEmail) targetParts.push(`email (${displayEmail})`);
  if (displayMobile) targetParts.push(`mobile (${displayMobile})`);
  const targetLabel = targetParts.join(' and ') || 'registered contact';

  return {
    emailSent: false,
    smsSent: false,
    mode: 'preview',
    channels,
    notice: `OTP is addressed to your registered ${targetLabel}. Add GMAIL_APP_PASSWORD (for email) or Twilio credentials (for SMS) in Settings → Secrets for live delivery.`,
    previewCode: params.otp,
  };
}
