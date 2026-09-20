import { useRef, useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { catalogueApi } from '@/api/endpoints/catalogue';
import { ApiError } from '@/api/errors';
import type { ProductDetail } from '@/api/types';
import { Badge, Button, Card, EmptyState, Notice } from '@/components/ui';
import { useToast } from '@/components/Toast';
import { Thumb } from '@/components/Thumb';

const ACCEPTED = ['image/jpeg', 'image/png', 'image/webp'];
const MAX_BYTES = 8 * 1024 * 1024;

/**
 * The gallery.
 *
 * Upload is three steps, and the middle one does not touch this API: ask the
 * application where to put the file, PUT the bytes straight into object
 * storage, then tell the application the key it landed under. That is the
 * backend's design, and it is the reason a 6MB photo from a phone camera does
 * not have to survive a round trip through an application server on a
 * connection that drops.
 */
export function ProductMediaPanel({ product }: { product: ProductDetail }) {
  const queryClient = useQueryClient();
  const toast = useToast();
  const fileInput = useRef<HTMLInputElement>(null);
  const [uploading, setUploading] = useState(false);
  const [order, setOrder] = useState<number[] | null>(null);

  const invalidate = () => {
    void queryClient.invalidateQueries({ queryKey: ['products'] });
  };

  const upload = useMutation({
    mutationFn: async (file: File) => {
      if (!ACCEPTED.includes(file.type)) {
        throw new ApiError({
          status: 400, path: 'upload',
          message: 'Photos must be JPEG, PNG or WebP.',
        });
      }
      if (file.size > MAX_BYTES) {
        throw new ApiError({
          status: 413, path: 'upload',
          message: 'That photo is larger than 8MB. Take it again at a smaller size.',
        });
      }

      const presigned = await catalogueApi.presignImage(file.type);

      const put = await fetch(presigned.uploadUrl, {
        method: 'PUT',
        body: file,
        headers: { 'Content-Type': file.type },
      });
      if (!put.ok) {
        throw new ApiError({
          status: put.status, path: 'storage',
          message: 'The photo did not upload. Check your connection and try again.',
        });
      }

      return catalogueApi.confirmMedia(product.id, {
        fileUrl: presigned.publicUrl,
        originalFilename: file.name,
        contentType: file.type,
        sizeBytes: file.size,
        makeDefault: product.media.length === 0,
      });
    },
    onSuccess: () => {
      toast.success('Photo added.');
      invalidate();
    },
    onError: (error) => {
      toast.error(error instanceof ApiError ? error.message : 'Could not add the photo.');
    },
    onSettled: () => setUploading(false),
  });

  const remove = useMutation({
    mutationFn: (mediaId: number) => catalogueApi.deleteMedia(product.id, mediaId),
    onSuccess: () => {
      toast.success('Photo removed.');
      invalidate();
    },
    onError: (error) => {
      toast.error(error instanceof ApiError ? error.message : 'Could not remove the photo.');
    },
  });

  const reorder = useMutation({
    mutationFn: (ids: number[]) => catalogueApi.reorderMedia(product.id, ids),
    onSuccess: () => {
      setOrder(null);
      toast.success('Order saved.');
      invalidate();
    },
    onError: (error) => {
      toast.error(error instanceof ApiError ? error.message : 'Could not save the order.');
    },
  });

  const media = order
    ? order.map((id) => product.media.find((m) => m.id === id)!).filter(Boolean)
    : [...product.media].sort((a, b) => a.sortOrder - b.sortOrder);

  function move(index: number, direction: -1 | 1) {
    const next = media.map((m) => m.id);
    const target = index + direction;
    if (target < 0 || target >= next.length) return;
    const moved = next[index]!;
    next[index] = next[target]!;
    next[target] = moved;
    setOrder(next);
  }

  async function onFiles(files: FileList | null) {
    if (!files || files.length === 0) return;
    setUploading(true);
    // One at a time: each needs its own presign, and a phone on a weak
    // connection does better with one large request than four.
    for (const file of Array.from(files)) {
      await upload.mutateAsync(file).catch(() => undefined);
    }
    if (fileInput.current) fileInput.current.value = '';
  }

  return (
    <div className="stack">
      <Card
        title="Photos"
        actions={
          <Button
            variant="primary"
            size="sm"
            onClick={() => fileInput.current?.click()}
            busy={uploading}
          >
            ＋ Add photos
          </Button>
        }
        flush
      >
        <input
          ref={fileInput}
          type="file"
          accept={ACCEPTED.join(',')}
          multiple
          className="sr-only"
          onChange={(event) => void onFiles(event.target.files)}
        />

        {media.length === 0 ? (
          <EmptyState
            icon="▣"
            title="No photos yet"
            action={
              <Button variant="primary" onClick={() => fileInput.current?.click()} busy={uploading}>
                Add the first photo
              </Button>
            }
          >
            The first photo is the one buyers see in search results. JPEG, PNG or WebP, up to 8MB.
          </EmptyState>
        ) : (
          <div className="list">
            {media.map((image, index) => (
              <div key={image.id} className="list__item">
                <Thumb src={image.url} alt={image.altText ?? ''} size="lg" />
                <div className="list__main">
                  <div className="list__title">
                    {index === 0 ? 'Main photo' : `Photo ${index + 1}`}
                  </div>
                  <div className="list__meta truncate">{image.originalFilename ?? image.url}</div>
                  {image.status !== 'READY' && (
                    <Badge tone={image.status === 'REJECTED' ? 'danger' : 'warn'}>
                      {image.status === 'REJECTED' ? 'Rejected' : 'Being checked'}
                    </Badge>
                  )}
                </div>
                <div className="row" style={{ gap: 4, flexWrap: 'nowrap' }}>
                  <Button
                    size="sm" variant="ghost" aria-label="Move earlier"
                    onClick={() => move(index, -1)} disabled={index === 0}
                  >↑</Button>
                  <Button
                    size="sm" variant="ghost" aria-label="Move later"
                    onClick={() => move(index, 1)} disabled={index === media.length - 1}
                  >↓</Button>
                  <Button
                    size="sm" variant="ghost" aria-label="Remove"
                    onClick={() => remove.mutate(image.id)} disabled={remove.isPending}
                  >✕</Button>
                </div>
              </div>
            ))}
          </div>
        )}
      </Card>

      {order && (
        <div className="row">
          <Button
            variant="primary"
            onClick={() => reorder.mutate(order)}
            busy={reorder.isPending}
          >
            Save this order
          </Button>
          <Button variant="ghost" onClick={() => setOrder(null)}>Undo</Button>
        </div>
      )}

      {product.moderation.editsNeedReview && (
        <Notice tone="warn">
          Adding or removing photos sends this listing back to be checked, so it comes off sale
          until somebody has looked at it.
        </Notice>
      )}
    </div>
  );
}
