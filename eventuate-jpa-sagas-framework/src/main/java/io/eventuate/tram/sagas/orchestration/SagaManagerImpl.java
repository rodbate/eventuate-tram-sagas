package io.eventuate.tram.sagas.orchestration;

import io.eventuate.javaclient.commonimpl.JSonMapper;
import io.eventuate.javaclient.spring.jdbc.IdGenerator;
import io.eventuate.tram.commands.common.ChannelMapping;
import io.eventuate.tram.commands.common.CommandMessageHeaders;
import io.eventuate.tram.commands.common.ReplyMessageHeaders;
import io.eventuate.tram.commands.consumer.CommandWithDestination;
import io.eventuate.tram.commands.producer.CommandProducer;
import io.eventuate.tram.events.common.EventMessageHeaders;
import io.eventuate.tram.events.publisher.DomainEventPublisher;
import io.eventuate.tram.events.subscriber.DomainEventEnvelopeImpl;
import io.eventuate.tram.messaging.common.Message;
import io.eventuate.tram.messaging.consumer.MessageConsumer;
import io.eventuate.tram.sagas.common.LockTarget;
import io.eventuate.tram.sagas.common.SagaCommandHeaders;
import io.eventuate.tram.sagas.common.SagaReplyHeaders;
import io.eventuate.tram.sagas.common.SagaUnlockCommand;
import io.eventuate.tram.sagas.participant.SagaLockManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import javax.annotation.PostConstruct;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static java.util.Collections.emptySet;
import static java.util.Collections.singleton;
import static java.util.stream.Collectors.joining;

@Component
public class SagaManagerImpl<Data>
        implements SagaManager<Data> {

  private Logger logger = LoggerFactory.getLogger(getClass());

  @Autowired
  private SagaInstanceRepository sagaInstanceRepository;

  @Autowired
  private CommandProducer commandProducer;

  @Autowired
  private MessageConsumer messageConsumer;

  @Autowired
  private IdGenerator idGenerator;


  @Autowired
  private AggregateInstanceSubscriptionsDAO aggregateInstanceSubscriptionsDAO;

  private EnlistedAggregatesDao enlistedAggregatesDao;

  @Autowired
  private ChannelMapping channelMapping;

  private Saga<Data> saga;

  public SagaManagerImpl(Saga<Data> saga) {
    this.saga = saga;
  }

  @Autowired
  private SagaLockManager sagaLockManager;


  public void setSagaCommandProducer(SagaCommandProducer sagaCommandProducer) {
    this.sagaCommandProducer = sagaCommandProducer;
  }

  @Autowired
  private SagaCommandProducer sagaCommandProducer;

  public void setSagaInstanceRepository(SagaInstanceRepository sagaInstanceRepository) {
    this.sagaInstanceRepository = sagaInstanceRepository;
  }

  public void setCommandProducer(CommandProducer commandProducer) {
    this.commandProducer = commandProducer;
  }

  public void setMessageConsumer(MessageConsumer messageConsumer) {
    this.messageConsumer = messageConsumer;
  }

  public void setIdGenerator(IdGenerator idGenerator) {
    this.idGenerator = idGenerator;
  }

  public void setAggregateInstanceSubscriptionsDAO(AggregateInstanceSubscriptionsDAO aggregateInstanceSubscriptionsDAO) {
    this.aggregateInstanceSubscriptionsDAO = aggregateInstanceSubscriptionsDAO;
  }

  public void setChannelMapping(ChannelMapping channelMapping) {
    this.channelMapping = channelMapping;
  }

  public void setSagaLockManager(SagaLockManager sagaLockManager) {
    this.sagaLockManager = sagaLockManager;
  }

  public void setDomainEventPublisher(DomainEventPublisher domainEventPublisher) {
    this.domainEventPublisher = domainEventPublisher;
  }

  @Override
  public SagaInstance create(Data sagaData) {
    return create(sagaData, Optional.empty());
  }

  @Override
  public SagaInstance create(Data data, Class targetClass, Object targetId) {
    return create(data, Optional.of(new LockTarget(targetClass, targetId).getTarget()));
  }

  @Override
  public SagaInstance create(Data sagaData, Optional<String> resource) {


    SagaInstance sagaInstance = new SagaInstance(getSagaType(),
            null,
            "????",
            null,
            SagaDataSerde.serializeSagaData(sagaData), new HashSet<>());

    sagaInstanceRepository.save(sagaInstance);

    String sagaId = sagaInstance.getId();

    resource.ifPresent( r -> Assert.isTrue(sagaLockManager.claimLock(getSagaType(), sagaId, r), "Cannot claim lock for resource"));

    SagaActions<Data> actions = getStateDefinition().getStartingHandler().get().apply(sagaData);

    List<CommandWithDestination> commands = actions.getCommands();

    sagaData = actions.getUpdatedSagaData().orElse(sagaData);

    sagaInstance.setLastRequestId(sendCommands(sagaId, commands));

    sagaInstance.setSerializedSagaData(SagaDataSerde.serializeSagaData(sagaData));

    publishEvents(sagaId, actions.getEventsToPublish(), actions.getUpdatedState());

    Optional<String> possibleNewState = actions.getUpdatedState();
    maybeUpdateState(sagaInstance, possibleNewState);
    maybePerformEndStateActions(sagaId, sagaInstance, possibleNewState);

    sagaInstanceRepository.update(sagaInstance);

    updateEnlistedAggregates(sagaId, actions.getEnlistedAggregates());

    updateEventInstanceSubscriptions(sagaData, sagaId, sagaInstance.getStateName());

    return sagaInstance;
  }

  private void publishEvents(String sagaId, Set<EventToPublish> eventsToPublish, Optional<String> updatedState) {
    Set<EnlistedAggregate> elas = emptySet(); //enlistedAggregatesDao.findEnlistedAggregates(sagaId);
    boolean isEndState = updatedState.filter(s -> getStateDefinition().isEndState(s)).isPresent();

    // TODO - an alternative model is to 'onResume()' based on a SagaCompletedEvent being published
    // Domain object doesn't have to publish event
    // What about automatically suspending if there is already a saga-in-progress? and delay the state check until much later

    for (EventToPublish event : eventsToPublish) {
      Map<String, String> headers = new HashMap<>();
      if (isEndState) {
        Set<String> sagaIds = enlistedAggregatesDao.findSagas(event.getAggregateType(), event.getAggregateId());
        sagaIds.remove(sagaId);
        headers.put("participating-saga-ids", sagaIds.stream().collect(joining(",")));
      }
      domainEventPublisher.publish(event.getAggregateType().getName(),
              event.getAggregateId(),
              headers,
              event.getDomainEvents());
    }
  }

  @Autowired
  private DomainEventPublisher domainEventPublisher;

  private void performEndStateActions(String sagaId, SagaInstance sagaInstance) {
    for (DestinationAndResource dr : sagaInstance.getDestinationsAndResources()) {
      Map<String, String> headers = new HashMap<>();
      headers.put(SagaCommandHeaders.SAGA_ID, sagaId);
      headers.put(SagaCommandHeaders.SAGA_TYPE, getSagaType()); // FTGO SagaCommandHandler failed without this but the OrdersAndCustomersIntegrationTest was fine?!?
      commandProducer.send(dr.getDestination(), dr.getResource(), new SagaUnlockCommand(), makeSagaReplyChannel(), headers);
    }
  }

  private SagaDefinition<Data> getStateDefinition() {
    SagaDefinition<Data> sm = saga.getSagaDefinition();
    Assert.notNull(sm, "state machine cannot be null");
    return sm;
  }

  private String getSagaType() {
    return saga.getSagaType();
  }


  @PostConstruct
  public void subscribeToReplyChannel() {
    // TODO subscribe to events that trigger the creation of a saga
    messageConsumer.subscribe(saga.getClass().getName() + "-consumer", singleton(channelMapping.transform(makeSagaReplyChannel())), this::handleMessage);
  }

  private String makeSagaReplyChannel() {
    return getSagaType() + "-reply";
  }


  private void updateEventInstanceSubscriptions(Data sagaData, String sagaId, String stateName) {
    List<EventClassAndAggregateId> instanceEvents = getStateDefinition().findEventHandlers(saga, stateName, sagaData);
    aggregateInstanceSubscriptionsDAO.update(getSagaType(), sagaId, instanceEvents);
  }

  private String sendCommands(String sagaId, List<CommandWithDestination> commands) {

    String lastRequestId = null;

    for (CommandWithDestination command : commands) {
      lastRequestId = idGenerator.genId().asString();
      sagaCommandProducer.sendCommand(getSagaType(), sagaId, command.getDestinationChannel(), command.getResource(), lastRequestId, command.getCommand(), makeSagaReplyChannel());
    }

    return lastRequestId;

  }


  public void handleMessage(Message message) {
    logger.debug("handle message invoked {}", message);
    if (message.hasHeader(SagaReplyHeaders.REPLY_SAGA_ID)) {
      handleReply(message);
    } else if (message.hasHeader(EventMessageHeaders.EVENT_TYPE)) {

      String aggregateType = message.getRequiredHeader(EventMessageHeaders.AGGREGATE_TYPE);
      String aggregateId = message.getRequiredHeader(Message.PARTITION_ID);
      String eventType = message.getRequiredHeader(EventMessageHeaders.EVENT_TYPE);
      // TODO query the saga event routing table: (at, aId, et) -> [(sagaType, sagaId)]
      for (SagaTypeAndId sagaTypeAndId : aggregateInstanceSubscriptionsDAO.findSagas(aggregateType, aggregateId, eventType)) {
        handleAggregateInstanceEvent(sagaTypeAndId.getSagaType(), sagaTypeAndId.getSagaId(), message, aggregateType, aggregateId, eventType);
      }
      ;


    } else {
      logger.warn("Handle message doesn't know what to do with: {} ", message);
    }
  }


  private void handleAggregateInstanceEvent(String sagaType, String sagaId, Message message, String aggregateType, String aggregateId, String eventType) {
    System.out.println("Got handleAggregateInstanceEvent: " + message + ", type=" + sagaType + ", instance=" + sagaId);

    SagaInstanceData<Data> sagaInstanceAndData = sagaInstanceRepository.findWithData(sagaType, sagaId);
    SagaInstance sagaInstance = sagaInstanceAndData.getSagaInstance();
    Data sagaData = sagaInstanceAndData.getSagaData();

    String currentState = sagaInstance.getStateName();
    logger.info("Current state={}", currentState);

    Optional<SagaEventHandler<Data>> eventHandler = getStateDefinition().findEventHandler(saga, currentState, sagaData, aggregateType, Long.parseLong(aggregateId), eventType);

    if (!eventHandler.isPresent()) {
      logger.error("No event handler for: {}", message);
      return;
    }

    logger.info("Invoking event handler for {}", message);


    SagaActions<Data> actions = eventHandler.get().getAction().apply(sagaData, new DomainEventEnvelopeImpl<>(null, null, null, null, null)); // TOOD

    // TODO - doesn't this do something??? Commands

    sagaInstance.setSerializedSagaData(SagaDataSerde.serializeSagaData(sagaData));
    sagaInstanceRepository.update(sagaInstance);

  }

  private void handleReply(Message message) {

    if (!isReplyForThisSagaType(message))
      return;

    logger.debug("Handle reply: {}", message);

    String sagaId = message.getRequiredHeader(SagaReplyHeaders.REPLY_SAGA_ID);
    String sagaType = message.getRequiredHeader(SagaReplyHeaders.REPLY_SAGA_TYPE);
    String requestId = message.getRequiredHeader(SagaReplyHeaders.REPLY_SAGA_REQUEST_ID);

    String messageId = message.getId();

    if (isDuplicateReply(messageId, sagaType, sagaId))
      return;


    String messageType = message.getRequiredHeader(ReplyMessageHeaders.REPLY_TYPE);
    String messageJson = message.getPayload();

    SagaInstanceData<Data> sagaInstanceAndData = sagaInstanceRepository.findWithData(sagaType, sagaId);
    SagaInstance sagaInstance = sagaInstanceAndData.getSagaInstance();
    Data sagaData = sagaInstanceAndData.getSagaData();


    message.getHeader(SagaReplyHeaders.REPLY_LOCKED).ifPresent(lockedTarget -> {
      String destination = message.getRequiredHeader(CommandMessageHeaders.inReply(CommandMessageHeaders.DESTINATION));
      sagaInstance.addDestinationsAndResources(singleton(new DestinationAndResource(destination, lockedTarget)));
    });

    String currentState = sagaInstance.getStateName();

    logger.info("Current state={}", currentState);
    Optional<ReplyClassAndHandler> replyHandler = getStateDefinition()
            .findReplyHandler(saga, sagaInstance, currentState, sagaData, requestId, message);

    if (!replyHandler.isPresent()) {
      logger.error("No handler for {}", message);
      return;
    }
    ReplyClassAndHandler m = replyHandler.get();

    Object param = JSonMapper.fromJson(messageJson, m.getReplyClass());

    SagaActions<Data> actions = (SagaActions<Data>) m.getReplyHandler().apply(sagaData, param);

    List<CommandWithDestination> commands = actions.getCommands();

    sagaData = actions.getUpdatedSagaData().orElse(sagaData);

    logger.info("Handled reply. Sending commands {}", commands);

    publishEvents(sagaId, actions.getEventsToPublish(), actions.getUpdatedState());

    Optional<String> possibleNewState = actions.getUpdatedState();
    maybeUpdateState(sagaInstance, possibleNewState);
    maybePerformEndStateActions(sagaId, sagaInstance, possibleNewState);
    updateEnlistedAggregates(sagaId, actions.getEnlistedAggregates());
    sagaInstance.setLastRequestId(sendCommands(sagaId, commands));
    updateEventInstanceSubscriptions(sagaData, sagaId, sagaInstance.getStateName());

    sagaInstance.setSerializedSagaData(SagaDataSerde.serializeSagaData(sagaData));

    sagaInstanceRepository.update(sagaInstance);

  }

  private Boolean isReplyForThisSagaType(Message message) {
    return message.getHeader(SagaReplyHeaders.REPLY_SAGA_TYPE).map(x -> x.equals(getSagaType())).orElse(false);
  }

  private DestinationAndResource toDestinationAndResource(CommandWithDestination commandToSend) {
    return new DestinationAndResource(commandToSend.getDestinationChannel(), commandToSend.getResource());
  }

  private void updateEnlistedAggregates(String sagaId, Set<EnlistedAggregate> enlistedAggregates) {
    // TODO enlistedAggregatesDao.save(sagaId, enlistedAggregates);
  }

  private void maybeUpdateState(SagaInstance sagaInstance, Optional<String> possibleNewState) {
    possibleNewState.ifPresent(sagaInstance::setStateName);
  }

  private void maybePerformEndStateActions(String sagaId, SagaInstance sagaInstance, Optional<String> possibleNewState) {
    possibleNewState.ifPresent(newState -> {
      if (getStateDefinition().isEndState(newState)) {
        performEndStateActions(sagaId, sagaInstance);
      }
    });
  }


  private boolean isDuplicateReply(String messageId, String sagaType, String sagaId) {
    String consumerId = makeConsumerIdFor(sagaType, sagaId);
    return false;
  }

  private String makeConsumerIdFor(String sagaType, String sagaId) {
    return "consumer-" + sagaType + "-" + sagaId;
  }


}
