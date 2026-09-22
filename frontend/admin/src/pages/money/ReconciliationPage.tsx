import { useQuery } from '@tanstack/react-query';
import { ledger as ledgerApi } from '@/api/endpoints';
import { Field } from '@/components/forms';
import { Money } from '@/components/Money';
import { Card, ErrorBanner, Loading, Muted, PageHeader, Pill } from '@/components/primitives';
import { keys, useFilters } from '@/hooks';

const DEFAULTS = { asOf: undefined as string | undefined };

/**
 * Escrow against payments against what has been paid out.
 *
 * Read per currency, and only per currency: the whole point of the screen is
 * that each currency's escrow must reconcile against its own payments, and a
 * single "platform total" would be the exact number that hides an imbalance in
 * one of them.
 */
export function ReconciliationPage() {
  const [filters, setFilters] = useFilters(DEFAULTS);

  const query = useQuery({
    queryKey: [...keys.reconciliation, filters.asOf],
    queryFn: () => ledgerApi.reconciliation(filters.asOf),
  });

  return (
    <>
      <PageHeader
        title="Reconciliation"
        description="What is held in escrow, against what was taken from buyers, against what has gone out to vendors. Each currency balances on its own or it does not balance."
      />

      <div className="filter-bar">
        <Field label="As of" hint="Leave empty for today.">
          <input
            className="input"
            type="date"
            value={filters.asOf ?? ''}
            onChange={(event) => setFilters({ asOf: event.target.value || undefined })}
          />
        </Field>
      </div>

      <ErrorBanner error={query.error} />
      {query.isLoading && <Loading what="Reconciling" />}

      {query.data && (
        <>
          {query.data.findings.length > 0 && (
            <Card title="Findings" tone="warning">
              <ul className="attention-list">
                {query.data.findings.map((finding, index) => (
                  <li key={index}>{finding}</li>
                ))}
              </ul>
            </Card>
          )}

          <Card title={`As of ${query.data.asOf}`} subtitle={query.data.message}>
            {query.data.lines.length === 0 ? (
              <Muted>Nothing to reconcile.</Muted>
            ) : (
              <div className="table-scroll">
                <table className="table">
                  <thead>
                    <tr>
                      <th scope="col">Currency</th>
                      <th scope="col" className="align-right">
                        Taken from buyers
                      </th>
                      <th scope="col" className="align-right">
                        Held in escrow
                      </th>
                      <th scope="col" className="align-right">
                        Available to vendors
                      </th>
                      <th scope="col" className="align-right">
                        Paid out
                      </th>
                      <th scope="col" className="align-right">
                        In flight
                      </th>
                      <th scope="col" className="align-right">
                        Difference
                      </th>
                      <th scope="col">Balanced</th>
                    </tr>
                  </thead>
                  <tbody>
                    {query.data.lines.map((line) => (
                      <tr key={line.currency} className={line.balanced ? undefined : 'row-overdue'}>
                        <th scope="row">{line.currency}</th>
                        <td className="align-right">
                          <Money amount={line.takenFromBuyers} currency={line.currency} />
                        </td>
                        <td className="align-right">
                          <Money amount={line.escrowHeld} currency={line.currency} />
                        </td>
                        <td className="align-right">
                          <Money amount={line.availableToVendors} currency={line.currency} />
                        </td>
                        <td className="align-right">
                          <Money amount={line.paidOut} currency={line.currency} />
                        </td>
                        <td className="align-right">
                          <Money amount={line.inFlight} currency={line.currency} />
                        </td>
                        <td className="align-right">
                          <Money amount={line.difference} currency={line.currency} signed />
                        </td>
                        <td>
                          <Pill tone={line.balanced ? 'good' : 'bad'}>
                            {line.balanced ? 'Balanced' : 'Out'}
                          </Pill>
                          {line.caveat && (
                            <>
                              <br />
                              <Muted>{line.caveat}</Muted>
                            </>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </Card>
        </>
      )}
    </>
  );
}
