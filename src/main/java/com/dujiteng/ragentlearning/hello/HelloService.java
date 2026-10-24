package com.dujiteng.ragentlearning.hello;

import org.springframework.stereotype.Service;

/** 当前业务：根据请求里的名字组织一条问候语。 */
@Service
public class HelloService {

    public String greet(String name) {
        return "你好，" + name + "。Ragent 学习工程已启动。";
    }
}
