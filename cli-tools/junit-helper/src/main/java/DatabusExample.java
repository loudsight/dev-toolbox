import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public class DatabusExample {
    interface Databus {
        interface Publisher<T> {
            public void publish(T publication);
        }
        interface Subscriber<T> {
            public void subscribe(Consumer<T> handler);
        }
        interface Topic {}

        <T> Publisher<T> makePublisher(Topic topic, Class<?> publishedType);

        <T> void makeSubscriber(Topic topic, Class<?> publishedType, Consumer<T> handler);
    }

    enum Topics implements Databus.Topic {
        PROCESS_ONE_STATUS,
        PROCESS_TWO_STATUS,

    }

    static class AeronDatabus implements Databus {

        Map<Topic, List<Consumer<Object>>> subscriberMap = new ConcurrentHashMap<>();
        @Override
        public <T> Publisher<T> makePublisher(Topic topic, Class<?> publishedType) {
            return publication -> subscriberMap.get(topic).forEach(it -> it.accept(publication));
        }

        @Override
        public <T> void makeSubscriber(Topic topic, Class<?> publishedType, Consumer<T> handler) {
            subscriberMap.compute(topic, (ignored, existingSubscribers) -> {
                var subscribers = existingSubscribers;

                if (subscribers == null) {
                    subscribers = new ArrayList<>();
                }
                subscribers.add((Consumer<Object>) handler);

                return subscribers;
            });
        }
    }

    abstract class SolaceDatabus implements Databus {

    }

    static Databus.Topic topicOne = new Databus.Topic() {
    };


    static class ProcessOne {
        int statusCode = 0;
        interface Status {
            int code();
        }
        private final Databus.Publisher<Status> publisher;
        public ProcessOne(Databus databus) {
            this.publisher = databus.makePublisher(Topics.PROCESS_ONE_STATUS, ProcessOne.Status.class);
        }

        public void publishStatus() {
            publisher.publish(() -> statusCode++);
        }

    }


    static class ProcessTwo {

        interface Status {
            int code();
        }

        private final Databus.Publisher<ProcessTwo.Status> publisher;
        public ProcessTwo(Databus databus) {
            databus.makeSubscriber(Topics.PROCESS_ONE_STATUS, ProcessOne.Status.class, this::handleStatus);
            this.publisher = databus.makePublisher(Topics.PROCESS_TWO_STATUS, ProcessTwo.Status.class);
        }

        public void handleStatus(ProcessOne.Status status) {
           publisher.publish(() -> status.code() + 1);
        }
    }
}
