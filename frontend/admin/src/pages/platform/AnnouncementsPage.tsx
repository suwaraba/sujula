import { useState } from 'react';
import { comms } from '@/api/endpoints';
import { NOTIFICATION_EVENTS, USER_ROLES } from '@/api/enums';
import { DeciderOnly } from '@/components/Decide';
import { Field, FormRow, NumberInput, Select, TextArea, TextInput } from '@/components/forms';
import { Card, ErrorBanner, Grid, Muted, PageHeader } from '@/components/primitives';
import { keys, useAction } from '@/hooks';

/**
 * Telling people things.
 *
 * Two shapes, deliberately kept apart. An announcement goes to a segment and
 * cannot be recalled; a notification goes to one person and is the one to reach
 * for when somebody is waiting on an answer. Putting them on one form is how a
 * message meant for one customer reaches forty thousand.
 */
export function AnnouncementsPage() {
  return (
    <>
      <PageHeader
        title="Announcements and notifications"
        description="An announcement reaches a segment and cannot be taken back. A notification reaches one person. They are separate forms because that difference is not one to make by leaving a field empty."
      />

      <DeciderOnly>
        <Grid columns={2}>
          <Announce />
          <Notify />
        </Grid>
      </DeciderOnly>
    </>
  );
}

function Announce() {
  const [title, setTitle] = useState('');
  const [body, setBody] = useState('');
  const [audienceRole, setAudienceRole] = useState<string>('');
  const [countryCode, setCountryCode] = useState('');
  const [event, setEvent] = useState<string>('PLATFORM_NOTICE');
  const [confirmed, setConfirmed] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [sent, setSent] = useState<string | null>(null);

  const action = useAction(
    () =>
      comms.announce({
        title: title.trim(),
        body: body.trim(),
        audienceRole: audienceRole ? (audienceRole as never) : null,
        countryCode: countryCode ? countryCode.toUpperCase() : null,
        event: event ? (event as never) : null,
      }),
    {
      invalidate: [keys.dashboard],
      onDone: (result) => {
        setSent(`${result.message} — ${result.recipients} of ${result.segmentSize} reached.`);
        setTitle('');
        setBody('');
        setConfirmed(false);
      },
    },
  );

  const audienceDescription = [
    audienceRole ? `${audienceRole.toLowerCase().replace(/_/g, ' ')} accounts` : 'everybody',
    countryCode ? `in ${countryCode.toUpperCase()}` : null,
  ]
    .filter(Boolean)
    .join(' ');

  return (
    <Card title="Tell a role, or a country, or everybody">
      <form
        className="action-form"
        onSubmit={async (formEvent) => {
          formEvent.preventDefault();
          setError(null);
          setSent(null);
          try {
            await action.mutateAsync();
          } catch (caught) {
            setError(caught);
          }
        }}
      >
        <ErrorBanner error={error} />
        {sent && <p className="notice notice-good">{sent}</p>}

        <FormRow>
          <Field label="Audience" hint="Empty reaches every account.">
            <Select
              value={audienceRole}
              options={USER_ROLES}
              placeholder="Everybody"
              onChange={setAudienceRole}
            />
          </Field>
          <Field label="Country" hint="Empty reaches every country.">
            <TextInput value={countryCode} maxLength={2} onChange={setCountryCode} />
          </Field>
        </FormRow>

        <Field label="Kind">
          <Select
            value={event}
            options={NOTIFICATION_EVENTS}
            placeholder="Platform notice"
            onChange={setEvent}
          />
        </Field>

        <Field label="Title" required>
          <TextInput value={title} required onChange={setTitle} />
        </Field>

        <Field label="Message" required>
          <TextArea value={body} required rows={6} onChange={setBody} />
        </Field>

        <label className="checkbox">
          <input
            type="checkbox"
            checked={confirmed}
            onChange={(changeEvent) => setConfirmed(changeEvent.target.checked)}
          />
          <span>
            I am sending this to <strong>{audienceDescription}</strong> and it cannot be recalled.
          </span>
        </label>

        <div className="form-actions">
          <button
            type="submit"
            className="button button-danger"
            disabled={!confirmed || !title.trim() || !body.trim() || action.isPending}
          >
            {action.isPending ? 'Sending…' : 'Send the announcement'}
          </button>
        </div>
      </form>
    </Card>
  );
}

function Notify() {
  const [userId, setUserId] = useState<number | ''>('');
  const [title, setTitle] = useState('');
  const [message, setMessage] = useState('');
  const [event, setEvent] = useState<string>('ACCOUNT_UPDATE');
  const [referenceId, setReferenceId] = useState('');
  const [error, setError] = useState<unknown>(null);
  const [sent, setSent] = useState<string | null>(null);

  const action = useAction(
    () =>
      comms.notify({
        userId: Number(userId),
        title: title.trim(),
        message: message.trim(),
        event: event ? (event as never) : null,
        referenceId: referenceId.trim() || null,
      }),
    {
      onDone: (result) =>
        setSent(
          result.delivered
            ? result.message
            : `${result.message} It was stored but not delivered — check the account's notification preferences.`,
        ),
    },
  );

  return (
    <Card title="Message one person">
      <form
        className="action-form"
        onSubmit={async (formEvent) => {
          formEvent.preventDefault();
          setError(null);
          setSent(null);
          try {
            await action.mutateAsync();
            setTitle('');
            setMessage('');
          } catch (caught) {
            setError(caught);
          }
        }}
      >
        <ErrorBanner error={error} />
        {sent && <p className="notice notice-good">{sent}</p>}

        <FormRow>
          <Field label="Account id" required>
            <NumberInput value={userId} required min={1} onChange={setUserId} />
          </Field>
          <Field label="Kind">
            <Select value={event} options={NOTIFICATION_EVENTS} placeholder="Choose" onChange={setEvent} />
          </Field>
        </FormRow>

        <Field label="Title" required>
          <TextInput value={title} required onChange={setTitle} />
        </Field>

        <Field label="Message" required>
          <TextArea value={message} required rows={6} onChange={setMessage} />
        </Field>

        <Field label="Reference" hint="A ticket, order number or dispute reference, so the reply has context.">
          <TextInput value={referenceId} onChange={setReferenceId} />
        </Field>

        <p className="field-hint">
          <Muted>
            This reaches them through whichever channels they have left switched on. Somebody with
            no email and no app still has SMS — which is the case this platform exists to serve.
          </Muted>
        </p>

        <div className="form-actions">
          <button
            type="submit"
            className="button button-primary"
            disabled={!userId || !title.trim() || !message.trim() || action.isPending}
          >
            {action.isPending ? 'Sending…' : 'Send it'}
          </button>
        </div>
      </form>
    </Card>
  );
}
