import { Type } from "@sinclair/typebox";
import { definePluginEntry } from "openclaw/plugin-sdk/plugin-entry";

type FetchDigestParams = {
  command?: string;
  commandName?: string;
  skillName?: string;
  limitStrong?: number;
  limitMedium?: number;
  onlyUnsent?: boolean;
  sinceCursor?: string;
};

type AckDigestParams = {
  command?: string;
  commandName?: string;
  skillName?: string;
  offerIds?: string[];
};

type RunDigestCycleParams = {
  command?: string;
  commandName?: string;
  skillName?: string;
  limitStrong?: number;
  limitMedium?: number;
  onlyUnsent?: boolean;
  sinceCursor?: string;
  autoAck?: boolean;
};

type ParsedRawCommand = {
  limitStrong?: number;
  limitMedium?: number;
  onlyUnsent?: boolean;
  sinceCursor?: string;
  offerIds?: string[];
  autoAck?: boolean;
};

const DEFAULT_LIMIT_STRONG = 5;
const DEFAULT_LIMIT_MEDIUM = 5;
const DEFAULT_ONLY_UNSENT = true;
const DEFAULT_TIMEOUT_MS = Number(process.env.CAREER_API_TIMEOUT_MS ?? "10000");
const DEFAULT_RETRY_ATTEMPTS = Number(process.env.CAREER_API_RETRY_ATTEMPTS ?? "2");
const DEFAULT_RETRY_BASE_DELAY_MS = Number(process.env.CAREER_API_RETRY_BASE_DELAY_MS ?? "350");
const RETRYABLE_STATUS = new Set([429, 500, 502, 503, 504]);

class CareerApiError extends Error {
  readonly statusCode?: number;

  constructor(message: string, statusCode?: number) {
    super(message);
    this.statusCode = statusCode;
  }
}

function requireEnv(name: string): string {
  const value = process.env[name];
  if (!value || value.trim().length === 0) {
    throw new Error(`${name} is required for career-digest plugin`);
  }
  return value;
}

function parseRawCommand(raw: string | undefined): ParsedRawCommand {
  if (!raw || raw.trim().length === 0) {
    return {};
  }

  const out: ParsedRawCommand = {};
  const tokens = raw.trim().split(/\s+/);

  for (const token of tokens) {
    const idx = token.indexOf("=");
    if (idx === -1) {
      continue;
    }

    const key = token.substring(0, idx).trim();
    const value = token.substring(idx + 1).trim();

    if (key === "limitStrong") {
      const parsed = Number(value);
      if (Number.isFinite(parsed)) out.limitStrong = parsed;
      continue;
    }

    if (key === "limitMedium") {
      const parsed = Number(value);
      if (Number.isFinite(parsed)) out.limitMedium = parsed;
      continue;
    }

    if (key === "onlyUnsent") {
      out.onlyUnsent = parseBoolean(value, true);
      continue;
    }

    if (key === "sinceCursor") {
      out.sinceCursor = value;
      continue;
    }

    if (key === "offerIds") {
      out.offerIds = value
        .split(",")
        .map((v) => v.trim())
        .filter(Boolean);
      continue;
    }

    if (key === "autoAck") {
      out.autoAck = parseBoolean(value, true);
      continue;
    }
  }

  return out;
}

function parseBoolean(value: string | undefined, defaultValue: boolean): boolean {
  if (!value || value.trim().length === 0) {
    return defaultValue;
  }

  const normalized = value.trim().toLowerCase();
  if (["1", "true", "yes", "y", "on"].includes(normalized)) {
    return true;
  }
  if (["0", "false", "no", "n", "off"].includes(normalized)) {
    return false;
  }
  return defaultValue;
}

function formatDigestMessage(digest: any, statusPayload: any, ackPayload: any): string {
  const generatedAt = digest?.generatedAt ?? "unknown";
  const state = digest?.status ?? "UNKNOWN";
  const pending = statusPayload?.pendingScoreCount ?? "?";
  const strong = digest?.unsentStrongCount ?? 0;
  const medium = digest?.unsentMediumCount ?? 0;
  const items = Array.isArray(digest?.items) ? digest.items : [];

  const lines: string[] = [];
  lines.push(`Career Digest ${generatedAt}`);
  lines.push(`State: ${state} | Pending score: ${pending} | Unsent S/M: ${strong}/${medium}`);

  if (items.length === 0) {
    lines.push("No new digest items.");
  } else {
    lines.push("");
    lines.push("Top matches:");
    for (const item of items) {
      const score = item?.score ?? "UNKNOWN";
      const title = item?.title ?? "(no title)";
      const company = item?.company ?? "(no company)";
      const location = item?.location ?? "(no location)";
      const reason = item?.shortReason ?? "";
      const url = item?.url ?? "";

      lines.push(`- [${score}] ${title} @ ${company} (${location})`);
      if (reason) lines.push(`  reason: ${reason}`);
      if (url) lines.push(`  url: ${url}`);
    }
  }

  if (ackPayload) {
    lines.push("");
    lines.push(`ACK updated: ${ackPayload.updatedCount ?? 0}`);
  }

  return lines.join("\n");
}

async function callCareerApi(path: string, init?: RequestInit) {
  const baseUrl = requireEnv("CAREER_API_BASE_URL").replace(/\/$/, "");
  const apiKey = requireEnv("CAREER_API_KEY");

  const attempts = Math.max(1, DEFAULT_RETRY_ATTEMPTS + 1);

  for (let attempt = 1; attempt <= attempts; attempt += 1) {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), DEFAULT_TIMEOUT_MS);

    try {
      const response = await fetch(`${baseUrl}${path}`, {
        ...init,
        signal: controller.signal,
        headers: {
          "Content-Type": "application/json",
          "X-API-Key": apiKey,
          ...(init?.headers ?? {}),
        },
      });

      const bodyText = await response.text();
      if (!response.ok) {
        const maybeRetry = RETRYABLE_STATUS.has(response.status) && attempt < attempts;
        if (maybeRetry) {
          await sleepWithBackoff(attempt);
          continue;
        }
        throw mapApiError(response.status, bodyText);
      }

      return bodyText.length > 0 ? JSON.parse(bodyText) : {};
    } catch (err: any) {
      if (err?.name === "AbortError") {
        if (attempt < attempts) {
          await sleepWithBackoff(attempt);
          continue;
        }
        throw new CareerApiError("Career API timeout. Increase CAREER_API_TIMEOUT_MS or retry later.");
      }

      if (err instanceof CareerApiError) {
        throw err;
      }

      if (attempt < attempts) {
        await sleepWithBackoff(attempt);
        continue;
      }

      throw new CareerApiError(`Career API request failed: ${err?.message ?? String(err)}`);
    } finally {
      clearTimeout(timeout);
    }
  }

  throw new CareerApiError("Career API call failed after retries");
}

function mapApiError(status: number, bodyText: string): CareerApiError {
  if (status === 401 || status === 403) {
    return new CareerApiError(
      "Career API auth failed (401/403). Verify CAREER_API_KEY and backend security config.",
      status,
    );
  }
  if (status === 429) {
    return new CareerApiError(
      "Career API rate limited (429). Reduce frequency or increase limits.",
      status,
    );
  }
  if (status >= 500) {
    return new CareerApiError(
      `Career API server error (${status}). Backend may be unavailable. Body: ${bodyText}`,
      status,
    );
  }
  return new CareerApiError(`Career API ${status}: ${bodyText}`, status);
}

function sleepWithBackoff(attempt: number): Promise<void> {
  const delayMs = DEFAULT_RETRY_BASE_DELAY_MS * Math.pow(2, Math.max(0, attempt - 1));
  return new Promise((resolve) => setTimeout(resolve, delayMs));
}

export default definePluginEntry({
  id: "career-digest",
  name: "Career Digest",
  description: "Fetch and acknowledge compact career digests from Java backend",
  register(api: any) {
    api.registerTool({
      name: "fetch_career_digest",
      description: "Fetches compact digest payload from Java backend API",
      parameters: Type.Object({
        command: Type.Optional(Type.String()),
        commandName: Type.Optional(Type.String()),
        skillName: Type.Optional(Type.String()),
        limitStrong: Type.Optional(Type.Number()),
        limitMedium: Type.Optional(Type.Number()),
        onlyUnsent: Type.Optional(Type.Boolean()),
        sinceCursor: Type.Optional(Type.String()),
      }),
      async execute(_id: string, params: FetchDigestParams) {
        const parsed = parseRawCommand(params.command);

        const limitStrong = Number(params.limitStrong ?? parsed.limitStrong ?? DEFAULT_LIMIT_STRONG);
        const limitMedium = Number(params.limitMedium ?? parsed.limitMedium ?? DEFAULT_LIMIT_MEDIUM);
        const onlyUnsent = Boolean(params.onlyUnsent ?? parsed.onlyUnsent ?? DEFAULT_ONLY_UNSENT);
        const sinceCursor = params.sinceCursor ?? parsed.sinceCursor;

        const query = new URLSearchParams({
          limitStrong: String(Math.max(0, limitStrong)),
          limitMedium: String(Math.max(0, limitMedium)),
          onlyUnsent: String(onlyUnsent),
        });
        if (sinceCursor && sinceCursor.length > 0) {
          query.set("sinceCursor", sinceCursor);
        }

        const payload = await callCareerApi(`/api/career/digest/compact?${query.toString()}`);

        return {
          content: [
            {
              type: "text",
              text: JSON.stringify(payload),
            },
          ],
        };
      },
    });

    api.registerTool({
      name: "ack_career_digest",
      description: "Acknowledges delivered digest offer IDs in Java backend API",
      parameters: Type.Object({
        command: Type.Optional(Type.String()),
        commandName: Type.Optional(Type.String()),
        skillName: Type.Optional(Type.String()),
        offerIds: Type.Optional(Type.Array(Type.String())),
      }),
      async execute(_id: string, params: AckDigestParams) {
        const parsed = parseRawCommand(params.command);
        const offerIds = (params.offerIds ?? parsed.offerIds ?? []).filter(Boolean);

        if (offerIds.length === 0) {
          return {
            content: [
              {
                type: "text",
                text: "No offerIds provided. Use: offerIds=<uuid1,uuid2,...>",
              },
            ],
          };
        }

        const payload = await callCareerApi("/api/career/digest/ack", {
          method: "POST",
          body: JSON.stringify({ offerIds }),
        });

        return {
          content: [
            {
              type: "text",
              text: JSON.stringify(payload),
            },
          ],
        };
      },
    });

    api.registerTool({
      name: "run_career_digest_cycle",
      description: "Runs full cycle: status, compact digest, and optional ack",
      parameters: Type.Object({
        command: Type.Optional(Type.String()),
        commandName: Type.Optional(Type.String()),
        skillName: Type.Optional(Type.String()),
        limitStrong: Type.Optional(Type.Number()),
        limitMedium: Type.Optional(Type.Number()),
        onlyUnsent: Type.Optional(Type.Boolean()),
        sinceCursor: Type.Optional(Type.String()),
        autoAck: Type.Optional(Type.Boolean()),
      }),
      async execute(_id: string, params: RunDigestCycleParams) {
        const parsed = parseRawCommand(params.command);

        const limitStrong = Number(params.limitStrong ?? parsed.limitStrong ?? DEFAULT_LIMIT_STRONG);
        const limitMedium = Number(params.limitMedium ?? parsed.limitMedium ?? DEFAULT_LIMIT_MEDIUM);
        const onlyUnsent = Boolean(params.onlyUnsent ?? parsed.onlyUnsent ?? DEFAULT_ONLY_UNSENT);
        const sinceCursor = params.sinceCursor ?? parsed.sinceCursor;
        const autoAck = Boolean(params.autoAck ?? parsed.autoAck ?? true);

        const statusPayload = await callCareerApi("/api/career/digest/status");

        const query = new URLSearchParams({
          limitStrong: String(Math.max(0, limitStrong)),
          limitMedium: String(Math.max(0, limitMedium)),
          onlyUnsent: String(onlyUnsent),
        });
        if (sinceCursor && sinceCursor.length > 0) {
          query.set("sinceCursor", sinceCursor);
        }

        const digestPayload = await callCareerApi(`/api/career/digest/compact?${query.toString()}`);

        let ackPayload: any = null;
        const offerIds = (Array.isArray(digestPayload?.items) ? digestPayload.items : [])
          .map((item: any) => item?.id)
          .filter((id: string | undefined) => Boolean(id));

        if (autoAck && offerIds.length > 0) {
          ackPayload = await callCareerApi("/api/career/digest/ack", {
            method: "POST",
            body: JSON.stringify({ offerIds }),
          });
        }

        const message = formatDigestMessage(digestPayload, statusPayload, ackPayload);
        const response = {
          status: statusPayload,
          digest: digestPayload,
          ack: ackPayload,
          message,
        };

        return {
          content: [
            {
              type: "text",
              text: JSON.stringify(response),
            },
          ],
        };
      },
    });
  },
});
