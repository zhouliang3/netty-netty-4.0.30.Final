package com.ilearn.netty4.test.recycler;

import io.netty.util.Recycler;

import java.util.concurrent.CountDownLatch;

/**
 * 功能说明:
 *
 * @author zhouliang
 * @Date 2016-06-08
 * <p/>
 * <p/>
 * 版本号 | 作者 | 修改时间 | 修改内容
 */
public class UserPool {
    final static Recycler<User> recycler = new Recycler<User>() {
        @Override
        protected User newObject(Handle handle) {
            return new User(handle);
        }
    };

    public static void main(String[] args) throws InterruptedException {
        int count = 10;
        final CountDownLatch latch = new CountDownLatch(count);
        for (int i = 0; i < count; i++) {
            final int v = i;
            new Thread() {
                public void run() {
                    User u = UserPool.recycler.get();
                    //System.out.println(String.valueOf(Thread.currentThread().getId() + " before: ") + u);
                    u.name = "name " + v;
                    u.passwd = "passwd " + v;
                    UserPool.recycler.recycle(u, u.handle);
                    User u1 = UserPool.recycler.get();
                    System.out.println(String.valueOf(Thread.currentThread().getId() + " after:::: ") + u1);
                    latch.countDown();
                }
            }.start();
        }
        latch.await();
    }

    static class User {
        String name;
        String passwd;
        Recycler.Handle handle;

        public User(Recycler.Handle handle) {
            this.handle = handle;
        }

        @Override
        public String toString() {
            return "User{" +
                    "name='" + name + '\'' +
                    ", passwd='" + passwd + '\'' +
                    '}';
        }
    }
}


