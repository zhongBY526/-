package com.intern.backed.graph;

import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;


import java.util.Map;

public class State extends AgentState {
    public static final String QUESTION = "question";
    public static final String TYPE = "type";
    public static final String ANSWER = "answer";
    public static final String CONTEXT = "retrievedContext";
    public static final String RETRY_COUNT = "retryCount";
    public static final String SUFFICIENT = "sufficient";
    public static final Map<String, Channel<?>> SCHEMA = Map.of(
            QUESTION, Channels.base(()->""),
            TYPE, Channels.base(()->""),
            ANSWER, Channels.base(()->""),
            CONTEXT, Channels.base(()->""),
            RETRY_COUNT, Channels.base(()->0),
            SUFFICIENT, Channels.base(()->false)
    );
    public State(Map<String, Object> initData) {
        super(initData);
    }
    public String question(){
        return this.<String>value(QUESTION).orElse(" ");
    }
    public String type(){
        return this.<String>value(TYPE).orElse(" ");
    }
    public String answer(){
        return this.<String>value(ANSWER).orElse(" ");
    }
    public String context(){
        return this.<String>value(CONTEXT).orElse(" ");
    }
    public int retryCount(){
        return this.<Integer>value(RETRY_COUNT).orElse(0);
    }
    public boolean sufficient(){
        return this.<Boolean>value(SUFFICIENT).orElse(false);
    }
}
