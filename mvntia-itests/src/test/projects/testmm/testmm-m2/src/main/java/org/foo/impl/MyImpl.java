package org.foo.impl;

import org.foo.api.MyInterface;
import org.foo.util.MyHelper;

public class MyImpl implements MyInterface {

    @Override
    public String greet(String who) {
        return MyHelper.sayHello(who);
    }

}
