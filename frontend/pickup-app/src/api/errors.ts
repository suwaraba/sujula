/** An answer from the API that was not a success. */
export class ApiError extends Error {
  readonly status: number;
  readonly path: string;
  readonly fieldErrors: Record<string, string>;

  constructor(args: {
    status: number;
    path: string;
    message: string;
    fieldErrors?: Record<string, string>;
  }) {
    super(args.message);
    this.name = 'ApiError';
    this.status = args.status;
    this.path = args.path;
    this.fieldErrors = args.fieldErrors ?? {};
  }

  /** Another operator's counter reads as absent — these rows lead to recipients' names. */
  get isNotFound(): boolean {
    return this.status === 404;
  }

  get isUnauthenticated(): boolean {
    return this.status === 401;
  }

  /** A wrong code, a full shelf, a deadline not yet reached. The message says which. */
  get isRefused(): boolean {
    return this.status === 400 || this.status === 409 || this.status === 422;
  }

  get isRateLimited(): boolean {
    return this.status === 429;
  }

  /** Nothing reached the server — the shop's wifi, most likely. */
  get isOffline(): boolean {
    return this.status === 0;
  }
}

export function parseErrorBody(
  status: number,
  path: string,
  body: unknown,
  fallback: string,
): ApiError {
  if (body && typeof body === 'object') {
    const b = body as Record<string, unknown>;

    const fieldErrors: Record<string, string> = {};
    const errors = b.errors ?? b.fieldErrors;
    if (errors && typeof errors === 'object' && !Array.isArray(errors)) {
      for (const [key, value] of Object.entries(errors as Record<string, unknown>)) {
        fieldErrors[key] = String(value);
      }
    }

    const message =
      (typeof b.message === 'string' && b.message) ||
      (typeof b.error === 'string' && b.error) ||
      fallback;

    return new ApiError({ status, path, message, fieldErrors });
  }

  if (typeof body === 'string' && body.trim()) {
    return new ApiError({ status, path, message: body.trim() });
  }

  return new ApiError({ status, path, message: fallback });
}
