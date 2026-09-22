/** An answer from the API that was not a success. */
export class ApiError extends Error {
  readonly status: number;
  readonly path: string;
  /** Field name -> message, when the server rejected a body field by field. */
  readonly fieldErrors: Record<string, string>;
  readonly code: string | undefined;

  constructor(args: {
    status: number;
    path: string;
    message: string;
    fieldErrors?: Record<string, string>;
    code?: string;
  }) {
    super(args.message);
    this.name = 'ApiError';
    this.status = args.status;
    this.path = args.path;
    this.fieldErrors = args.fieldErrors ?? {};
    this.code = args.code;
  }

  /** A row that is not this seller's reads as absent, which is the backend's deliberate answer. */
  get isNotFound(): boolean {
    return this.status === 404;
  }

  get isUnauthenticated(): boolean {
    return this.status === 401;
  }

  get isForbidden(): boolean {
    return this.status === 403;
  }

  get isConflict(): boolean {
    return this.status === 409;
  }

  get isRateLimited(): boolean {
    return this.status === 429;
  }

  /** Nothing reached the server — offline, DNS, a dropped connection mid-flight. */
  get isOffline(): boolean {
    return this.status === 0;
  }
}

/** Reads whatever shape of error body the application happens to have sent. */
export function parseErrorBody(
  status: number,
  path: string,
  body: unknown,
  fallback: string,
): ApiError {
  if (body && typeof body === 'object') {
    const b = body as Record<string, unknown>;

    const fieldErrors: Record<string, string> = {};
    const errors = b.errors ?? b.fieldErrors ?? b.violations;
    if (errors && typeof errors === 'object' && !Array.isArray(errors)) {
      for (const [k, v] of Object.entries(errors as Record<string, unknown>)) {
        fieldErrors[k] = String(v);
      }
    } else if (Array.isArray(errors)) {
      for (const e of errors) {
        if (e && typeof e === 'object') {
          const item = e as Record<string, unknown>;
          const field = item.field ?? item.property ?? item.objectName;
          const msg = item.message ?? item.defaultMessage;
          if (field && msg) fieldErrors[String(field)] = String(msg);
        }
      }
    }

    const message =
      (typeof b.message === 'string' && b.message) ||
      (typeof b.error === 'string' && b.error) ||
      (typeof b.detail === 'string' && b.detail) ||
      fallback;

    return new ApiError({
      status,
      path,
      message,
      fieldErrors,
      code: typeof b.code === 'string' ? b.code : undefined,
    });
  }

  if (typeof body === 'string' && body.trim()) {
    return new ApiError({ status, path, message: body.trim() });
  }

  return new ApiError({ status, path, message: fallback });
}
