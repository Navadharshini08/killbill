/*
 * Copyright 2010-2013 Ning, Inc.
 * Copyright 2014-2019 Groupon, Inc
 * Copyright 2014-2019 The Billing Project, LLC
 *
 * The Billing Project licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.killbill.billing.invoice.dao;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.annotation.Nullable;
import javax.inject.Inject;

import org.joda.time.DateTime;
import org.joda.time.LocalDate;
import org.killbill.billing.ErrorCode;
import org.killbill.billing.account.api.Account;
import org.killbill.billing.callcontext.InternalCallContext;
import org.killbill.billing.callcontext.InternalTenantContext;
import org.killbill.billing.catalog.api.Currency;
import org.killbill.billing.invoice.InvoiceDispatcher.FutureAccountNotifications;
import org.killbill.billing.invoice.api.Invoice;
import org.killbill.billing.invoice.api.InvoiceApiException;
import org.killbill.billing.invoice.api.InvoicePaymentStatus;
import org.killbill.billing.invoice.api.InvoiceStatus;
import org.killbill.billing.junction.BillingEventSet;
import org.killbill.billing.util.api.AuditLevel;
import org.killbill.billing.util.audit.AuditLogWithHistory;
import org.killbill.billing.util.entity.DefaultPagination;
import org.killbill.billing.util.entity.Pagination;
import org.killbill.billing.util.entity.dao.MockEntityDaoBase;
import org.killbill.bus.api.PersistentBus;

public class MockInvoiceDao extends MockEntityDaoBase<InvoiceModelDao, Invoice, InvoiceApiException> implements InvoiceDao {

    private final PersistentBus eventBus;
    private final Object monitor = new Object();
    private final Map<UUID, InvoiceModelDao> invoices = new LinkedHashMap<>();
    private final Map<UUID, InvoiceItemModelDao> items = new LinkedHashMap<>();
    private final Map<UUID, InvoicePaymentModelDao> payments = new LinkedHashMap<>();
    private final Map<UUID, Long> accountRecordIds = new HashMap<>();

    @Inject
    public MockInvoiceDao(final PersistentBus eventBus) {
        this.eventBus = eventBus;
    }


    @Override
    public void setFutureAccountNotificationsForEmptyInvoice(final UUID accountId, final FutureAccountNotifications callbackDateTimePerSubscriptions, final InternalCallContext context) {

    }

    @Override
    public InvoiceStatus getInvoiceStatus(final UUID invoiceId, final InternalTenantContext context) throws InvoiceApiException {
        synchronized (monitor) {
            final InvoiceModelDao invoice =  invoices.get(invoiceId);
            if (invoice == null) {
                throw new InvoiceApiException(ErrorCode.INVOICE_NOT_FOUND, invoiceId);
            }
            return invoice.getStatus();
        }
    }

    @Override
    public void rescheduleInvoiceNotification(final UUID accountId, final DateTime nextRescheduleDt, final InternalCallContext context) {

    }

    @Override
    public List<InvoiceItemModelDao> createInvoices(final Iterable<InvoiceModelDao> invoiceModelDaos,
                                                    @Nullable final BillingEventSet billingEvents,
                                                    final Set<InvoiceTrackingModelDao> trackingIds,
                                                    final FutureAccountNotifications callbackDateTimePerSubscriptions,
                                                    @Nullable final ExistingInvoiceMetadata existingInvoiceMetadataOrNull,
                                                    final boolean returnCreatedInvoiceItems,
                                                    final InternalCallContext context) {
        synchronized (monitor) {
            final List<InvoiceItemModelDao> createdItems = new LinkedList<>();
            for (final InvoiceModelDao invoice : invoiceModelDaos) {
                createdItems.addAll(storeInvoice(invoice, context));
            }
            return createdItems;
        }
    }

    private UUID getAccountIdByRecordId(final Long accountRecordId) {
        return accountRecordIds.entrySet().stream()
                .filter(entry -> accountRecordId.equals(entry.getValue()))
                .findFirst().get().getKey();
    }

    private Collection<InvoiceItemModelDao> storeInvoice(final InvoiceModelDao invoice, final InternalCallContext context) {
        final Collection<InvoiceItemModelDao> createdItems = new LinkedList<>();

        invoices.put(invoice.getId(), invoice);
        for (final InvoiceItemModelDao invoiceItemModelDao : invoice.getInvoiceItems()) {
            final InvoiceItemModelDao oldItemOrNull = items.put(invoiceItemModelDao.getId(), invoiceItemModelDao);
            if (oldItemOrNull == null) {
                createdItems.add(invoiceItemModelDao);
            }
        }
        accountRecordIds.put(invoice.getAccountId(), context.getAccountRecordId());

        return createdItems;
    }

    @Override
    public InvoiceModelDao getById(final UUID id, final InternalTenantContext context) {
        synchronized (monitor) {
            return invoices.get(id);
        }
    }

    @Override
    public InvoiceModelDao getById(final UUID id, final boolean includeRepairStatus, final InternalTenantContext context) {
        synchronized (monitor) {
            return invoices.get(id);
        }
    }

    @Override
    public InvoiceModelDao getByNumber(final Integer number, final Boolean includeInvoiceChildren, final Boolean includeTrackingIds, final InternalTenantContext context) {
        synchronized (monitor) {
            for (final InvoiceModelDao invoice : invoices.values()) {
                if (invoice.getInvoiceNumber().equals(number)) {
                    return invoice;
                }
            }
        }

        return null;
    }

    @Override
    public InvoiceModelDao getByInvoiceItem(final UUID invoiceItemId, final Boolean includeTrackingIds, final InternalTenantContext context) throws InvoiceApiException {
        final InvoiceItemModelDao item = items.get(invoiceItemId);
        return (item != null) ? invoices.get(item.getInvoiceId()) : null;
    }

    @Override
    public List<InvoiceModelDao> getInvoicesByGroup(final UUID groupId, final Boolean includeTrackingIds, final InternalTenantContext context) {
        return null;
    }

    @Override
    public Pagination<InvoiceModelDao> getAll(final InternalTenantContext context) {
        synchronized (monitor) {
            return new DefaultPagination<InvoiceModelDao>((long) invoices.values().size(), invoices.values().iterator());
        }
    }

    @Override
    public List<InvoiceModelDao> getInvoicesByAccount(final Boolean includeVoidedInvoices, final Boolean includeInvoiceComponents, final Boolean includeTrackingIds, final InternalTenantContext context) {
        final List<InvoiceModelDao> result = new ArrayList<InvoiceModelDao>();

        synchronized (monitor) {
            final UUID accountId = getAccountIdByRecordId(context.getAccountRecordId());
            for (final InvoiceModelDao invoice : invoices.values()) {
                if (accountId.equals(invoice.getAccountId()) && !invoice.isMigrated()) {
                    if (includeInvoiceComponents) {
                        invoice.addInvoiceItems(items.values().stream().filter(item -> item.getInvoiceId().equals(invoice.getId())).collect(Collectors.toList()));
                    }
                    result.add(invoice);
                }
            }
        }
        return result;
    }

    @Override
    public List<InvoiceModelDao> getInvoicesByAccount(final Boolean includeVoidedInvoices, final LocalDate fromDate, final LocalDate upToDate, final Boolean includeInvoiceComponents, final Boolean includeTrackingIds, final InternalTenantContext context) {
        final List<InvoiceModelDao> invoicesForAccount = new ArrayList<>();
        synchronized (monitor) {
            final UUID accountId = getAccountIdByRecordId(context.getAccountRecordId());
            for (final InvoiceModelDao invoice : getAll(context)) {
                if (accountId.equals(invoice.getAccountId()) && !invoice.getTargetDate().isBefore(fromDate) && !invoice.isMigrated() &&
                    (includeVoidedInvoices || !InvoiceStatus.VOID.equals(invoice.getStatus()))) {
                    if (includeInvoiceComponents) {
                        invoice.addInvoiceItems(items.values().stream().filter(item -> item.getInvoiceId().equals(invoice.getId())).collect(Collectors.toList()));
                    }
                    invoicesForAccount.add(invoice);
                }
            }
        }

        return invoicesForAccount;
    }

    @Override
    public Pagination<InvoiceModelDao> searchInvoices(final String searchKey, final Long offset, final Long limit, final InternalTenantContext context) {
        final List<InvoiceModelDao> results = new LinkedList<>();
        int maxNbRecords = 0;
        for (final InvoiceModelDao invoice : getAll(context)) {
            maxNbRecords++;
            if (invoice.getId().toString().equals(searchKey) ||
                invoice.getAccountId().toString().equals(searchKey) ||
                invoice.getInvoiceNumber().toString().equals(searchKey) ||
                invoice.getCurrency().toString().equals(searchKey)) {
                results.add(invoice);
            }
        }

        return DefaultPagination.build(offset, limit, maxNbRecords, results);
    }

    @Override
    public void test(final InternalTenantContext context) {
    }

    @Override
    public UUID getInvoiceIdByPaymentId(final UUID paymentId, final InternalTenantContext context) {
        synchronized (monitor) {
            for (final InvoicePaymentModelDao payment : payments.values()) {
                if (paymentId.equals(payment.getPaymentId())) {
                    return payment.getInvoiceId();
                }
            }
        }
        return null;
    }

    @Override
    public List<InvoicePaymentModelDao> getInvoicePaymentsByPaymentId(final UUID paymentId, final InternalTenantContext context) {
        final List<InvoicePaymentModelDao> result = new LinkedList<>();
        synchronized (monitor) {
            for (final InvoicePaymentModelDao payment : payments.values()) {
                if (paymentId.equals(payment.getPaymentId())) {
                    result.add(payment);
                }
            }
        }
        return result;
    }

    @Override
    public List<InvoicePaymentModelDao> getInvoicePaymentsByInvoice(final UUID invoiceId, final InternalTenantContext context) {
        final List<InvoicePaymentModelDao> result = new LinkedList<>();
        synchronized (monitor) {
            for (final InvoicePaymentModelDao payment : payments.values()) {
                if (invoiceId.equals(payment.getInvoiceId())) {
                    result.add(payment);
                }
            }
        }
        return result;
    }

    @Override
    public List<InvoicePaymentModelDao> getInvoicePaymentsByAccount(final InternalTenantContext context) {

        throw new UnsupportedOperationException();
/*
        InvoicePaymentModelDao does not export accountId ?

        final List<InvoicePaymentModelDao> invoicesForAccount = new ArrayList<InvoicePaymentModelDao>();
        synchronized (monitor) {
            final UUID accountId = getAccountIdByRecordId(context.getAccountRecordId());
            for (final InvoicePaymentModelDao payment : payments.values()) {
            }
        }
        return null;
*/
    }

    @Override
    public InvoicePaymentModelDao getInvoicePaymentByCookieId(final String cookieId, final InternalTenantContext internalTenantContext) {
        throw new UnsupportedOperationException();
    }

    @Override
    public InvoicePaymentModelDao getInvoicePayment(final UUID invoicePaymentId, final InternalTenantContext internalTenantContext) {
        return null;
    }

    @Override
    public void notifyOfPaymentCompletion(final InvoicePaymentModelDao invoicePayment, final UUID paymentAttemptId, final InternalCallContext context) {
        synchronized (monitor) {
            payments.put(invoicePayment.getId(), invoicePayment);
        }
    }

    @Override
    public void consumeExstingCBAOnAccountWithUnpaidInvoices(final UUID accountId, final InternalCallContext context) {
    }

    @Override
    public BigDecimal getAccountBalance(final UUID accountId, final InternalTenantContext context) {
        BigDecimal balance = BigDecimal.ZERO;

        for (final InvoiceModelDao invoice : getAll(context)) {
            if (accountId.equals(invoice.getAccountId())) {
                balance = balance.add(InvoiceModelDaoHelper.getRawBalanceForRegularInvoice(invoice));
            }
        }

        return balance;
    }

    @Override
    public List<InvoiceModelDao> getUnpaidInvoicesByAccountId(final UUID accountId, final LocalDate startDate, final LocalDate upToDate, final InternalTenantContext context) {
        final List<InvoiceModelDao> unpaidInvoices = new ArrayList<>();

        for (final InvoiceModelDao invoice : getAll(context)) {
            if (accountId.equals(invoice.getAccountId()) && (InvoiceModelDaoHelper.getRawBalanceForRegularInvoice(invoice).compareTo(BigDecimal.ZERO) > 0) && !invoice.isMigrated()) {
                unpaidInvoices.add(invoice);
            }
        }

        return unpaidInvoices;
    }

    @Override
    public List<InvoiceModelDao> getAllInvoicesByAccount(final Boolean includeVoidedInvoices, final Boolean includeInvoiceComponents, final InternalTenantContext context) {
        final List<InvoiceModelDao> result = new ArrayList<>();

        synchronized (monitor) {
            final UUID accountId = getAccountIdByRecordId(context.getAccountRecordId());
            for (final InvoiceModelDao invoice : invoices.values()) {
                if (accountId.equals(invoice.getAccountId()) && (includeVoidedInvoices || !InvoiceStatus.VOID.equals(invoice.getStatus()))) {
                    if (includeInvoiceComponents) {
                        invoice.addInvoiceItems(items.values().stream().filter(item -> item.getInvoiceId().equals(invoice.getId())).collect(Collectors.toList()));
                    }
                    result.add(invoice);
                }
            }
        }
        return result;
    }

    @Override
    public InvoicePaymentModelDao postChargeback(final UUID invoicePaymentId, final UUID paymentAttemptId, final String chargebackTransactionExternalKey, final BigDecimal amount, final Currency currency, final InternalCallContext context) throws InvoiceApiException {
        throw new UnsupportedOperationException();
    }

    @Override
    public InvoicePaymentModelDao postChargebackReversal(final UUID paymentId, final UUID paymentAttemptId, final String chargebackTransactionExternalKey, final InternalCallContext context) throws InvoiceApiException {
        throw new UnsupportedOperationException();
    }

    @Override
    public InvoiceItemModelDao doCBAComplexity(final InvoiceModelDao invoice, final InternalCallContext context) throws InvoiceApiException {
        // Do nothing unless we need it..
        return null;
    }

    @Override
    public Map<UUID, BigDecimal> computeItemAdjustments(final String invoiceId, final Map<UUID, BigDecimal> invoiceItemIdsWithNullAmounts, final InternalTenantContext context) throws InvoiceApiException {
        throw new UnsupportedOperationException();
    }

    @Override
    public BigDecimal getRemainingAmountPaid(final UUID invoicePaymentId, final InternalTenantContext context) {
        throw new UnsupportedOperationException();
    }

    @Override
    public UUID getAccountIdFromInvoicePaymentId(final UUID invoicePaymentId, final InternalTenantContext context) throws InvoiceApiException {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<InvoicePaymentModelDao> getChargebacksByAccountId(final UUID accountId, final InternalTenantContext context) {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<InvoicePaymentModelDao> getChargebacksByPaymentId(final UUID paymentId, final InternalTenantContext context) {
        throw new UnsupportedOperationException();
    }

    @Override
    public InvoicePaymentModelDao getChargebackById(final UUID chargebackId, final InternalTenantContext context) throws InvoiceApiException {
        throw new UnsupportedOperationException();
    }

    @Override
    public InvoiceItemModelDao getExternalChargeById(final UUID externalChargeId, final InternalTenantContext context) throws InvoiceApiException {
        throw new UnsupportedOperationException();
    }

    @Override
    public InvoiceItemModelDao getCreditById(final UUID creditId, final InternalTenantContext context) throws InvoiceApiException {
        throw new UnsupportedOperationException();
    }

    @Override
    public BigDecimal getAccountCBA(final UUID accountId, final InternalTenantContext context) {
        return null;
    }

    @Override
    public InvoicePaymentModelDao createRefund(final UUID paymentId, final UUID paymentAttemptId, final BigDecimal amount, final boolean isInvoiceAdjusted,
                                               final Map<UUID, BigDecimal> invoiceItemIdsWithAmounts, final String transactionExternalKey,
                                               final InvoicePaymentStatus status, final InternalCallContext context)
            throws InvoiceApiException {
        return null;
    }

    @Override
    public void deleteCBA(final UUID accountId, final UUID invoiceId, final UUID invoiceItemId, final InternalCallContext context) throws InvoiceApiException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void notifyOfPaymentInit(final InvoicePaymentModelDao invoicePayment, final UUID paymentAttemptId, final InternalCallContext context) {
        synchronized (monitor) {
            payments.put(invoicePayment.getId(), invoicePayment);
        }

    }

    @Override
    public void changeInvoiceStatus(final UUID invoiceId, final InvoiceStatus newState, final InternalCallContext context) throws InvoiceApiException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void createParentChildInvoiceRelation(final InvoiceParentChildModelDao invoiceRelation, final InternalCallContext context) throws InvoiceApiException {
        throw new UnsupportedOperationException();
    }

    @Override
    public InvoiceModelDao getParentDraftInvoice(final UUID parentAccountId, final InternalCallContext context) throws InvoiceApiException {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<InvoiceParentChildModelDao> getChildInvoicesByParentInvoiceId(final UUID parentInvoiceId, final InternalCallContext context) throws InvoiceApiException {
        throw new UnsupportedOperationException();
    }

    public void updateInvoiceItemAmount(final UUID invoiceItemId, final BigDecimal amount, final InternalCallContext context) throws InvoiceApiException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void transferChildCreditToParent(final Account childAccount, final InternalCallContext context) throws InvoiceApiException {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<InvoiceItemModelDao> getInvoiceItemsByParentInvoice(final UUID parentInvoiceId, final InternalTenantContext context) throws InvoiceApiException {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<InvoiceTrackingModelDao> getTrackingsByDateRange(final LocalDate startDate, final LocalDate endDate, final InternalCallContext context) {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<AuditLogWithHistory> getInvoiceAuditLogsWithHistoryForId(final UUID invoiceId, final AuditLevel auditLevel, final InternalTenantContext context) {
        return null;
    }

    @Override
    public List<AuditLogWithHistory> getInvoiceItemAuditLogsWithHistoryForId(final UUID invoiceItemId, final AuditLevel auditLevel, final InternalTenantContext context) {
        return null;
    }

    @Override
    public List<AuditLogWithHistory> getInvoicePaymentAuditLogsWithHistoryForId(final UUID invoicePaymentId, final AuditLevel auditLevel, final InternalTenantContext context) {
        return null;
    }

}
