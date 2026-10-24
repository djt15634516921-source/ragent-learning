package com.dujiteng.ragentlearning.hello;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 接收 HTTP 请求，把业务处理交给 Service，再返回处理结果。 */
@RestController
@RequestMapping("/api")
public class HelloController {

    private final HelloService helloService;

    // Spring 调用这个构造方法，传入它管理的 HelloService 对象。
    public HelloController(HelloService helloService) {
        this.helloService = helloService;
    }

    @GetMapping(value = "/hello", produces = "text/plain;charset=UTF-8")
    public String hello(@RequestParam(name = "name", defaultValue = "同学") String name) {
        return helloService.greet(name);
    }
}
