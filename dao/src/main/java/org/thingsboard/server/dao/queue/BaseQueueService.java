/**
 * Copyright © 2016-2022 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.server.dao.queue;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.thingsboard.server.common.data.TenantProfile;
import org.thingsboard.server.common.data.id.QueueId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.page.PageData;
import org.thingsboard.server.common.data.page.PageLink;
import org.thingsboard.server.common.data.queue.ProcessingStrategy;
import org.thingsboard.server.common.data.queue.Queue;
import org.thingsboard.server.common.data.queue.SubmitStrategy;
import org.thingsboard.server.common.data.queue.SubmitStrategyType;
import org.thingsboard.server.dao.entity.AbstractEntityService;
import org.thingsboard.server.dao.exception.DataValidationException;
import org.thingsboard.server.dao.service.DataValidator;
import org.thingsboard.server.dao.service.PaginatedRemover;
import org.thingsboard.server.dao.service.Validator;
import org.thingsboard.server.dao.tenant.TbTenantProfileCache;

import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class BaseQueueService extends AbstractEntityService implements QueueService {

    @Autowired
    private QueueDao queueDao;

    @Autowired
    private TbTenantProfileCache tenantProfileCache;

//    @Autowired
//    private QueueStatsService queueStatsService;

    @Override
    public Queue saveQueue(Queue queue) {
        log.trace("Executing createOrUpdateQueue [{}]", queue);
        queueValidator.validate(queue, Queue::getTenantId);
        return queueDao.save(queue.getTenantId(), queue);
    }

    @Override
    public void deleteQueue(TenantId tenantId, QueueId queueId) {
        log.trace("Executing deleteQueue, queueId: [{}]", queueId);
        try {
            queueDao.removeById(tenantId, queueId.getId());
        } catch (Exception t) {
            ConstraintViolationException e = extractConstraintViolationException(t).orElse(null);
            if (e != null && e.getConstraintName() != null && e.getConstraintName().equalsIgnoreCase("fk_default_queue_device_profile")) {
                throw new DataValidationException("The queue referenced by the device profiles cannot be deleted!");
            } else {
                throw t;
            }
        }
    }

    @Override
    public List<Queue> findQueuesByTenantId(TenantId tenantId) {
        log.trace("Executing findQueues, tenantId: [{}]", tenantId);
        return queueDao.findAllByTenantId(getSystemOrIsolatedTenantId(tenantId));
    }

    @Override
    public PageData<Queue> findQueuesByTenantId(TenantId tenantId, PageLink pageLink) {
        log.trace("Executing findQueues pageLink [{}]", pageLink);
        Validator.validatePageLink(pageLink);
        return queueDao.findQueuesByTenantId(getSystemOrIsolatedTenantId(tenantId), pageLink);
    }

    @Override
    public List<Queue> findAllQueues() {
        log.trace("Executing findAllQueues");
        return queueDao.findAllQueues();
    }

    @Override
    public Queue findQueueById(TenantId tenantId, QueueId queueId) {
        log.trace("Executing findQueueById, queueId: [{}]", queueId);
        return queueDao.findById(tenantId, queueId.getId());
    }

    @Override
    public Queue findQueueByTenantIdAndName(TenantId tenantId, String queueName) {
        log.trace("Executing findQueueByTenantIdAndName, tenantId: [{}] queueName: [{}]", tenantId, queueName);
        return queueDao.findQueueByTenantIdAndName(getSystemOrIsolatedTenantId(tenantId), queueName);
    }

    @Override
    public Queue findQueueByTenantIdAndNameInternal(TenantId tenantId, String queueName) {
        log.trace("Executing findQueueByTenantIdAndNameInternal, tenantId: [{}] queueName: [{}]", tenantId, queueName);
        return queueDao.findQueueByTenantIdAndName(tenantId, queueName);
    }

    @Override
    public void deleteQueuesByTenantId(TenantId tenantId) {
        Validator.validateId(tenantId, "Incorrect tenant id for delete queues request.");
        tenantQueuesRemover.removeEntities(tenantId, tenantId);
    }

    private DataValidator<Queue> queueValidator =
            new DataValidator<>() {

                @Override
                protected void validateCreate(TenantId tenantId, Queue queue) {
                    if (queueDao.findQueueByTenantIdAndTopic(tenantId, queue.getTopic()) != null) {
                        throw new DataValidationException(String.format("Queue with topic: %s already exists!", queue.getTopic()));
                    }
                    if (queueDao.findQueueByTenantIdAndName(tenantId, queue.getName()) != null) {
                        throw new DataValidationException(String.format("Queue with name: %s already exists!", queue.getName()));
                    }
                }

                @Override
                protected void validateUpdate(TenantId tenantId, Queue queue) {
                    Queue foundQueue = queueDao.findById(tenantId, queue.getUuidId());
                    if (queueDao.findById(tenantId, queue.getUuidId()) == null) {
                        throw new DataValidationException(String.format("Queue with id: %s does not exists!", queue.getId()));
                    }
                    if (!foundQueue.getName().equals(queue.getName())) {
                        throw new DataValidationException("Queue name can't be changed!");
                    }
                    if (!foundQueue.getTopic().equals(queue.getTopic())) {
                        throw new DataValidationException("Queue topic can't be changed!");
                    }
                }

                @Override
                protected void validateDataImpl(TenantId tenantId, Queue queue) {
                    if (!tenantId.equals(TenantId.SYS_TENANT_ID)) {
                        TenantProfile tenantProfile = tenantProfileCache.get(tenantId);

                        if (!tenantProfile.isIsolatedTbRuleEngine()) {
                            throw new DataValidationException("Tenant should be isolated!");
                        }
                    }

                    if (StringUtils.isEmpty(queue.getName())) {
                        throw new DataValidationException("Queue name should be specified!");
                    }
                    if (StringUtils.isBlank(queue.getTopic())) {
                        throw new DataValidationException("Queue topic should be non empty and without spaces!");
                    }
                    if (queue.getPollInterval() < 1) {
                        throw new DataValidationException("Queue poll interval should be more then 0!");
                    }
                    if (queue.getPartitions() < 1) {
                        throw new DataValidationException("Queue partitions should be more then 0!");
                    }
                    if (queue.getPackProcessingTimeout() < 1) {
                        throw new DataValidationException("Queue pack processing timeout should be more then 0!");
                    }

                    SubmitStrategy submitStrategy = queue.getSubmitStrategy();
                    if (submitStrategy == null) {
                        throw new DataValidationException("Queue submit strategy can't be null!");
                    }
                    if (submitStrategy.getType() == null) {
                        throw new DataValidationException("Queue submit strategy type can't be null!");
                    }
                    if (submitStrategy.getType() == SubmitStrategyType.BATCH && submitStrategy.getBatchSize() < 1) {
                        throw new DataValidationException("Queue submit strategy batch size should be more then 0!");
                    }
                    ProcessingStrategy processingStrategy = queue.getProcessingStrategy();
                    if (processingStrategy == null) {
                        throw new DataValidationException("Queue processing strategy can't be null!");
                    }
                    if (processingStrategy.getType() == null) {
                        throw new DataValidationException("Queue processing strategy type can't be null!");
                    }
                    if (processingStrategy.getRetries() < 0) {
                        throw new DataValidationException("Queue processing strategy retries can't be less then 0!");
                    }
                    if (processingStrategy.getFailurePercentage() < 0 || processingStrategy.getFailurePercentage() > 100) {
                        throw new DataValidationException("Queue processing strategy failure percentage should be in a range from 0 to 100!");
                    }
                    if (processingStrategy.getPauseBetweenRetries() < 0) {
                        throw new DataValidationException("Queue processing strategy pause between retries can't be less then 0!");
                    }
                    if (processingStrategy.getMaxPauseBetweenRetries() < processingStrategy.getPauseBetweenRetries()) {
                        throw new DataValidationException("Queue processing strategy MAX pause between retries can't be less then pause between retries!");
                    }
                }
            };

    private PaginatedRemover<TenantId, Queue> tenantQueuesRemover =
            new PaginatedRemover<>() {

                @Override
                protected PageData<Queue> findEntities(TenantId tenantId, TenantId id, PageLink pageLink) {
                    return queueDao.findQueuesByTenantId(id, pageLink);
                }

                @Override
                protected void removeEntity(TenantId tenantId, Queue entity) {
                    deleteQueue(tenantId, entity.getId());
                }
            };

    private TenantId getSystemOrIsolatedTenantId(TenantId tenantId) {
        if (!tenantId.equals(TenantId.SYS_TENANT_ID)) {
            TenantProfile tenantProfile = tenantProfileCache.get(tenantId);
            if (tenantProfile.isIsolatedTbRuleEngine()) {
                return tenantId;
            }
        }

        return TenantId.SYS_TENANT_ID;
    }
}
