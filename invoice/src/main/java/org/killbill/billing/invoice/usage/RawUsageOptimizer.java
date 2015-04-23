/*
 * Copyright 2014-2015 Groupon, Inc
 * Copyright 2014-2015 The Billing Project, LLC
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

package org.killbill.billing.invoice.usage;

import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;
import javax.inject.Inject;

import org.joda.time.LocalDate;
import org.killbill.billing.callcontext.InternalCallContext;
import org.killbill.billing.catalog.api.BillingPeriod;
import org.killbill.billing.catalog.api.Usage;
import org.killbill.billing.invoice.api.InvoiceItem;
import org.killbill.billing.invoice.model.UsageInvoiceItem;
import org.killbill.billing.usage.InternalUserApi;
import org.killbill.billing.usage.RawUsage;
import org.killbill.billing.util.config.InvoiceConfig;
import org.killbill.clock.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Ordering;

public class RawUsageOptimizer {

    private static final Ordering<InvoiceItem> USAGE_ITEM_ORDERING = Ordering.natural()
                                                                             .onResultOf(new Function<InvoiceItem, Comparable>() {
                                                                                 @Override
                                                                                 public Comparable apply(final InvoiceItem invoiceItem) {
                                                                                     return invoiceItem.getEndDate();
                                                                                 }
                                                                             });

    private static final Logger log = LoggerFactory.getLogger(RawUsageOptimizer.class);

    private final InternalUserApi usageApi;
    private final InvoiceConfig config;

    @Inject
    public RawUsageOptimizer(final InvoiceConfig config, final InternalUserApi usageApi) {
        this.usageApi = usageApi;
        this.config = config;
    }

    public List<RawUsage> getConsumableInArrearUsage(final LocalDate firstEventStartDate, final LocalDate targetDate, final Iterable<InvoiceItem> existingUsageItems, final Map<String, Usage> knownUsage, final InternalCallContext internalCallContext) {
        final LocalDate targetStartDate = config.getMaxRawUsagePreviousPeriod() > 0 ? getOptimizedRawUsageStartDate(firstEventStartDate, targetDate, existingUsageItems, knownUsage) : firstEventStartDate;
        return usageApi.getRawUsageForAccount(targetStartDate, targetDate, internalCallContext);
    }

    @VisibleForTesting
    LocalDate getOptimizedRawUsageStartDate(final LocalDate firstEventStartDate, final LocalDate targetDate, final Iterable<InvoiceItem> existingUsageItems, final Map<String, Usage> knownUsage) {

        if (!existingUsageItems.iterator().hasNext()) {
            return firstEventStartDate;

        }
        // Extract all usage billing period known in that catalog
        final Set<BillingPeriod> knownUsageBillingPeriod = ImmutableSet.copyOf(Iterables.transform(knownUsage.values(), new Function<Usage, BillingPeriod>() {
            @Nullable
            @Override
            public BillingPeriod apply(final Usage input) {
                return input.getBillingPeriod();
            }
        }));

        // Make sure all usage items are sorted by endDate
        final List<InvoiceItem> sortedUsageItems = USAGE_ITEM_ORDERING.sortedCopy(existingUsageItems);

        // Compute an array with one date per BillingPeriod:
        // If BillingPeriod is never defined in the catalog (no need to look for items), we initialize its value such that
        final LocalDate[] perBillingPeriodMostRecentConsumableInArrearItemEndDate = new LocalDate[BillingPeriod.values().length];
        int idx = 0;
        for (BillingPeriod bp : BillingPeriod.values()) {
            final LocalDate makerDateThanCannotBeChosenAsTheMinOfAllDates = targetDate.plusMonths(config.getMaxRawUsagePreviousPeriod() * bp.getNumberOfMonths());
            perBillingPeriodMostRecentConsumableInArrearItemEndDate[idx++] = (knownUsageBillingPeriod.contains(bp)) ? null : makerDateThanCannotBeChosenAsTheMinOfAllDates;
        }

        final ListIterator<InvoiceItem> iterator = sortedUsageItems.listIterator(sortedUsageItems.size());
        while (iterator.hasPrevious()) {

            final UsageInvoiceItem item = (UsageInvoiceItem) iterator.previous();
            final Usage usage = knownUsage.get(item.getUsageName());

            if (perBillingPeriodMostRecentConsumableInArrearItemEndDate[usage.getBillingPeriod().ordinal()] == null) {
                perBillingPeriodMostRecentConsumableInArrearItemEndDate[usage.getBillingPeriod().ordinal()] = item.getEndDate();
                if (!Iterables.any(ImmutableList.copyOf(perBillingPeriodMostRecentConsumableInArrearItemEndDate), new Predicate<LocalDate>() {
                    @Override
                    public boolean apply(@Nullable final LocalDate input) {
                        return input == null;
                    }
                })) {
                    break;
                }
            }
        }

        // Extract the min from all the dates
        LocalDate targetStartDate = null;
        idx = 0;
        for (BillingPeriod bp : BillingPeriod.values()) {
            if (bp != BillingPeriod.NO_BILLING_PERIOD) {
                final LocalDate tmp = perBillingPeriodMostRecentConsumableInArrearItemEndDate[idx];
                final LocalDate targetBillingPeriodDate = tmp != null ? tmp.minusMonths(config.getMaxRawUsagePreviousPeriod() * bp.getNumberOfMonths()) : null;
                if (targetStartDate == null || (targetBillingPeriodDate != null && targetBillingPeriodDate.compareTo(targetStartDate) < 0)) {
                    targetStartDate = targetBillingPeriodDate;
                }
            }
            idx++;
        }

        final LocalDate result = targetStartDate.compareTo(firstEventStartDate) > 0 ? targetStartDate : firstEventStartDate;
        log.info("RawUsageOptimizer rawEventStartDate = {}, firstEventStartDate = {}", result, firstEventStartDate);
        return result;
    }

}