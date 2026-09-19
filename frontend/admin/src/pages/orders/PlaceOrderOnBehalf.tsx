import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { orders as ordersApi } from '@/api/endpoints';
import type { PlaceOrderLine } from '@/api/requests';
import { ActionModal } from '@/components/ActionModal';
import { DecideButton } from '@/components/Decide';
import { Field, FormRow, NumberInput, TextArea } from '@/components/forms';
import { keys, useAction } from '@/hooks';

const EMPTY_LINE: PlaceOrderLine = { productId: 0, variantId: null, quantity: 1 };

/**
 * Placing an order for somebody who telephoned.
 *
 * The delivery address is asked for as an id belonging to the customer, and
 * there is deliberately no field for the customer's own location: the parcel
 * goes where the address says, which on this marketplace is routinely a
 * different country from wherever the person on the telephone is.
 */
export function PlaceOrderOnBehalf() {
  const navigate = useNavigate();
  const [open, setOpen] = useState(false);
  const [customerId, setCustomerId] = useState<number | ''>('');
  const [deliveryAddressId, setDeliveryAddressId] = useState<number | ''>('');
  const [reason, setReason] = useState('');
  const [notes, setNotes] = useState('');
  const [lines, setLines] = useState<PlaceOrderLine[]>([{ ...EMPTY_LINE }]);

  const action = useAction(ordersApi.placeOnBehalf, {
    invalidate: [keys.orders, keys.dashboard],
    message: (placed) => placed.message || `Order ${placed.orderNumber} placed.`,
    onDone: (placed) => navigate(`/orders/${placed.orderId}`),
  });

  function reset() {
    setCustomerId('');
    setDeliveryAddressId('');
    setReason('');
    setNotes('');
    setLines([{ ...EMPTY_LINE }]);
  }

  return (
    <>
      <DecideButton variant="secondary" onClick={() => setOpen(true)}>
        Place an order on behalf
      </DecideButton>

      <ActionModal
        open={open}
        width="wide"
        title="Place an order for somebody who telephoned"
        description="The order is placed as the customer, against one of their own saved addresses. It is audited under your name with the reason you give."
        submitLabel="Place the order"
        onClose={() => {
          setOpen(false);
          reset();
        }}
        onSubmit={async () => {
          await action.mutateAsync({
            customerId: Number(customerId),
            deliveryAddressId: Number(deliveryAddressId),
            reason: reason.trim(),
            notes: notes.trim() || null,
            lines: lines.filter((line) => line.productId > 0 && line.quantity > 0),
          });
          reset();
        }}
      >
        <FormRow>
          <Field label="Customer account id" required>
            <NumberInput value={customerId} required min={1} onChange={setCustomerId} />
          </Field>
          <Field
            label="Delivery address id"
            required
            hint="One of the customer's own saved addresses. This is where the parcel goes — it has nothing to do with where the customer is."
          >
            <NumberInput value={deliveryAddressId} required min={1} onChange={setDeliveryAddressId} />
          </Field>
        </FormRow>

        <fieldset className="line-editor">
          <legend>Lines</legend>
          {lines.map((line, index) => (
            <FormRow key={index} columns={4}>
              <Field label="Product id" required>
                <NumberInput
                  value={line.productId || ''}
                  required
                  min={1}
                  onChange={(productId) =>
                    setLines((current) =>
                      current.map((item, position) =>
                        position === index ? { ...item, productId: Number(productId) || 0 } : item,
                      ),
                    )
                  }
                />
              </Field>
              <Field label="Variant id" hint="optional">
                <NumberInput
                  value={line.variantId ?? ''}
                  min={1}
                  onChange={(variantId) =>
                    setLines((current) =>
                      current.map((item, position) =>
                        position === index
                          ? { ...item, variantId: variantId === '' ? null : Number(variantId) }
                          : item,
                      ),
                    )
                  }
                />
              </Field>
              <Field label="Quantity" required>
                <NumberInput
                  value={line.quantity}
                  required
                  min={1}
                  onChange={(quantity) =>
                    setLines((current) =>
                      current.map((item, position) =>
                        position === index ? { ...item, quantity: Number(quantity) || 1 } : item,
                      ),
                    )
                  }
                />
              </Field>
              <div className="line-remove">
                <button
                  type="button"
                  className="button button-ghost"
                  disabled={lines.length === 1}
                  onClick={() => setLines((current) => current.filter((_, p) => p !== index))}
                >
                  Remove
                </button>
              </div>
            </FormRow>
          ))}
          <button
            type="button"
            className="button button-ghost"
            onClick={() => setLines((current) => [...current, { ...EMPTY_LINE }])}
          >
            Add a line
          </button>
        </fieldset>

        <Field label="Why you are placing this" required>
          <TextArea
            value={reason}
            required
            minLength={5}
            onChange={setReason}
            placeholder="Customer telephoned; could not complete checkout on their phone."
          />
        </Field>

        <Field label="Note on the order" hint="Seen by the vendors fulfilling it.">
          <TextArea value={notes} onChange={setNotes} />
        </Field>
      </ActionModal>
    </>
  );
}
