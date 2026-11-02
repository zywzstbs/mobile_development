package cn.gulupai.server.dice.application;

@FunctionalInterface
public interface RandomSource {
    int nextInt(int bound);
}
