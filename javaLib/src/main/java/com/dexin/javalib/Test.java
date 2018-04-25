package com.dexin.javalib;

import java.util.concurrent.ArrayBlockingQueue;

/**
 * 生产者-消费者 测试
 */
public class Test {
    private ArrayBlockingQueue<Integer> queue = new ArrayBlockingQueue<>(10);

    public static void main(String[] args) {
        Test test = new Test();
        test.new Producer().start();
        test.new Consumer().start();
    }

    class Consumer extends Thread {
        @Override
        public void run() {
            while (true) {
                try {
                    System.out.println("消费:" + queue.take());
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

    class Producer extends Thread {
        @Override
        public void run() {
            while (true) {
                try {
                    System.out.println("生产:");
                    queue.put(1);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }
}
