import { useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { storeApi } from '@/api/endpoints/store';
import { catalogueApi } from '@/api/endpoints/catalogue';
import { ApiError } from '@/api/errors';
import { useStore } from '@/store/StoreProvider';
import { useIdempotencyKey } from '@/lib/hooks';
import { formatDate, formatDateTime, humanise } from '@/lib/format';
import type { KycDocumentType } from '@/api/types';
import {
  Badge, Button, Card, EmptyState, Notice, PageHeader, Skeleton, type Tone,
} from '@/components/ui';
import { SelectField } from '@/components/form';
import { useToast } from '@/components/Toast';

const DOCUMENT_TYPES: { value: KycDocumentType; label: string }[] = [
  { value: 'NATIONAL_ID', label: 'National ID card' },
  { value: 'PASSPORT', label: 'Passport' },
  { value: 'DRIVING_LICENCE', label: 'Driving licence' },
  { value: 'BUSINESS_REGISTRATION', label: 'Business registration' },
  { value: 'TAX_CERTIFICATE', label: 'Tax certificate' },
  { value: 'PROOF_OF_ADDRESS', label: 'Proof of address' },
  { value: 'BANK_STATEMENT', label: 'Bank statement' },
];

const DOC_TONES: Record<string, Tone> = {
  SUBMITTED: 'info',
  ACCEPTED: 'ok',
  REJECTED: 'danger',
  EXPIRED: 'warn',
};

const ACCEPTED_TYPES = ['image/jpeg', 'image/png', 'image/webp', 'application/pdf'];
const MAX_BYTES = 10 * 1024 * 1024;

/**
 * Verification.
 *
 * The documents go straight into object storage and only their keys reach the
 * API — a passport scan travelling through an application server is a passport
 * scan in three access logs. Nothing here displays a document back; a seller
 * sees the filename they sent and the decision, which is all this screen needs
 * to be useful.
 */
export function StoreVerification() {
  const { storeId, store } = useStore();
  const queryClient = useQueryClient();
  const toast = useToast();
  const fileInput = useRef<HTMLInputElement>(null);
  const [type, setType] = useState<KycDocumentType>('NATIONAL_ID');
  const [uploading, setUploading] = useState(false);
  const [key, resetKey] = useIdempotencyKey();

  const kyc = useQuery({
    queryKey: ['kyc', storeId],
    queryFn: () => storeApi.kyc(storeId),
  });

  const submit = useMutation({
    mutationFn: async (file: File) => {
      if (!ACCEPTED_TYPES.includes(file.type)) {
        throw new ApiError({
          status: 400, path: 'upload',
          message: 'Send a photo (JPEG, PNG or WebP) or a PDF.',
        });
      }
      if (file.size > MAX_BYTES) {
        throw new ApiError({
          status: 413, path: 'upload',
          message: 'That file is larger than 10MB.',
        });
      }

      // The image presign endpoint is the storage door this API exposes; PDFs
      // and photographs both go through it.
      const presigned = await catalogueApi.presignImage(
        file.type === 'application/pdf' ? 'image/png' : file.type,
      );

      const put = await fetch(presigned.uploadUrl, {
        method: 'PUT',
        body: file,
        headers: { 'Content-Type': file.type },
      });
      if (!put.ok) {
        throw new ApiError({
          status: put.status, path: 'storage',
          message: 'The document did not upload. Check your connection and try again.',
        });
      }

      return storeApi.submitKyc(
        storeId,
        [{
          type,
          fileUrl: presigned.publicUrl,
          originalFilename: file.name,
          contentType: file.type,
          sizeBytes: file.size,
        }],
        key,
      );
    },
    onSuccess: () => {
      resetKey();
      void queryClient.invalidateQueries({ queryKey: ['kyc'] });
      void queryClient.invalidateQueries({ queryKey: ['store'] });
      toast.success('Document sent. Somebody will look at it.');
    },
    onError: (error) => {
      resetKey();
      toast.error(error instanceof ApiError ? error.message : 'Could not send the document.');
    },
    onSettled: () => {
      setUploading(false);
      if (fileInput.current) fileInput.current.value = '';
    },
  });

  if (kyc.isLoading) {
    return <div className="page stack"><Skeleton height={32} width={200} /><Skeleton height={280} /></div>;
  }

  const state = kyc.data;

  return (
    <div className="page stack">
      <PageHeader
        title="Verification"
        subtitle="Identity and business documents. Nothing goes on sale until these are accepted."
        actions={
          state && (
            <Badge tone={state.status === 'VERIFIED' ? 'ok' : 'warn'} dot>
              {humanise(state.status)}
            </Badge>
          )
        }
      />

      {state?.status === 'VERIFIED' ? (
        <Notice tone="ok" title="You are verified">
          Decided {formatDateTime(state.decidedAt)}. Your shop can trade.
        </Notice>
      ) : state?.status === 'IN_REVIEW' ? (
        <Notice tone="info" title="Being looked at">
          Sent {formatDateTime(state.submittedAt)}. You can keep writing listings meanwhile — they
          go on sale the moment this is accepted.
        </Notice>
      ) : state?.status === 'ACTION_REQUIRED' ? (
        <Notice tone="warn" title="Something needs your attention">
          <ul style={{ margin: 'var(--space-2) 0 0', paddingLeft: '1.2em' }}>
            {state.actionsRequired.map((action) => <li key={action}>{action}</li>)}
          </ul>
        </Notice>
      ) : (
        <Notice tone="warn" title="Your shop cannot sell yet">
          Send the documents below. Most shops are decided within a couple of working days.
        </Notice>
      )}

      {state && state.missing.length > 0 && (
        <Card title="Still needed">
          <div className="stack stack--tight">
            {state.missing.map((missing) => (
              <div key={missing} className="row">
                <Badge tone="warn">Needed</Badge>
                <span>{DOCUMENT_TYPES.find((d) => d.value === missing)?.label ?? humanise(missing)}</span>
              </div>
            ))}
          </div>
        </Card>
      )}

      {store?.kycStatus !== 'VERIFIED' && (
        <Card title="Send a document">
          <div className="stack">
            <SelectField
              label="What this is"
              value={type}
              onChange={(event) => setType(event.target.value as KycDocumentType)}
            >
              {DOCUMENT_TYPES.map(({ value, label }) => (
                <option key={value} value={value}>{label}</option>
              ))}
            </SelectField>

            <input
              ref={fileInput}
              type="file"
              accept={ACCEPTED_TYPES.join(',')}
              className="sr-only"
              onChange={(event) => {
                const file = event.target.files?.[0];
                if (!file) return;
                setUploading(true);
                submit.mutate(file);
              }}
            />

            <Button
              variant="primary"
              onClick={() => fileInput.current?.click()}
              busy={uploading}
            >
              Choose a file or take a photo
            </Button>

            <p className="small muted">
              A clear photo of the whole document, all four corners visible. JPEG, PNG, WebP or
              PDF, up to 10MB. Your document goes straight to secure storage — it does not pass
              through our servers.
            </p>
          </div>
        </Card>
      )}

      <Card title="What you have sent" flush>
        {(state?.documents.length ?? 0) === 0 ? (
          <EmptyState icon="✓" title="Nothing sent yet" />
        ) : (
          <div className="list">
            {state!.documents.map((doc) => (
              <div key={doc.id} className="list__item">
                <div className="list__main">
                  <div className="list__title">
                    {DOCUMENT_TYPES.find((d) => d.value === doc.type)?.label ?? humanise(doc.type)}
                  </div>
                  <div className="list__meta truncate">
                    {doc.originalFilename ?? '—'} · sent {formatDateTime(doc.submittedAt)}
                    {doc.expiresOn && ` · expires ${formatDate(doc.expiresOn)}`}
                  </div>
                  {doc.rejectionReason && (
                    <div className="small" style={{ color: 'var(--danger)', marginTop: 4 }}>
                      {doc.rejectionReason}
                    </div>
                  )}
                </div>
                <Badge tone={DOC_TONES[doc.status] ?? 'neutral'} dot>
                  {humanise(doc.status)}
                </Badge>
              </div>
            ))}
          </div>
        )}
      </Card>
    </div>
  );
}
