package com.oilquiz.app.ai.chat.event;

public interface StreamingSubscriber {
    void onEvent(StreamingEvent event);

    default String getSubscriberId() {
        return this.getClass().getName() + "@" + Integer.toHexString(hashCode());
    }
}
