#  Copyright 2022 G-Research
#
#  Licensed under the Apache License, Version 2.0 (the "License");
#  you may not use this file except in compliance with the License.
#  You may obtain a copy of the License at
#
#       http://www.apache.org/licenses/LICENSE-2.0
#
#  Unless required by applicable law or agreed to in writing, software
#  distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
#  limitations under the License.

import abc
import dataclasses
from dataclasses import dataclass

from py4j.java_gateway import JVMView, JavaObject
from pyspark.sql import Column
from pyspark.sql.functions import abs, greatest, lit
from pyspark.sql.types import DataType

from gresearch.spark import _is_column


class DiffComparator(abc.ABC):
    @abc.abstractmethod
    def equiv(self, left: Column, right: Column) -> Column:
        pass


class DiffComparators:
    @staticmethod
    def default() -> 'DefaultDiffComparator':
        return DefaultDiffComparator()

    @staticmethod
    def nullSafeEqual() -> 'NullSafeEqualDiffComparator':
        return NullSafeEqualDiffComparator()

    @staticmethod
    def epsilon(epsilon: float) -> 'EpsilonDiffComparator':
        assert isinstance(epsilon, float), epsilon
        return EpsilonDiffComparator(epsilon)

    @staticmethod
    def string(whitespace_agnostic: bool = True) -> 'StringDiffComparator':
        assert isinstance(whitespace_agnostic, bool), whitespace_agnostic
        return StringDiffComparator(whitespace_agnostic)

    @staticmethod
    def duration(duration: str) -> 'DurationDiffComparator':
        assert isinstance(duration, str), duration
        return DurationDiffComparator(duration)

    @staticmethod
    def map(key_type: DataType, value_type: DataType, key_order_sensitive: bool = False) -> 'MapDiffComparator':
        assert isinstance(key_type, DataType), key_type
        assert isinstance(value_type, DataType), value_type
        assert isinstance(key_order_sensitive, bool), key_order_sensitive
        return MapDiffComparator(key_type, value_type, key_order_sensitive)


class NullSafeEqualDiffComparator(DiffComparator):
    def equiv(self, left: Column, right: Column) -> Column:
        assert _is_column(left), left
        assert _is_column(right), right
        return left.eqNullSafe(right)


class DefaultDiffComparator(NullSafeEqualDiffComparator):
    # for testing only
    def _to_java(self, jvm: JVMView) -> JavaObject:
        return jvm.uk.co.gresearch.spark.diff.DiffComparators.default()


@dataclass(frozen=True)
class EpsilonDiffComparator(DiffComparator):
    epsilon: float
    relative: bool = True
    inclusive: bool = True

    def as_relative(self) -> 'EpsilonDiffComparator':
        return dataclasses.replace(self, relative=True)

    def as_absolute(self) -> 'EpsilonDiffComparator':
        return dataclasses.replace(self, relative=False)

    def as_inclusive(self) -> 'EpsilonDiffComparator':
        return dataclasses.replace(self, inclusive=True)

    def as_exclusive(self) -> 'EpsilonDiffComparator':
        return dataclasses.replace(self, inclusive=False)

    def equiv(self, left: Column, right: Column) -> Column:
        assert _is_column(left), left
        assert _is_column(right), right

        threshold = greatest(abs(left), abs(right)) * self.epsilon if self.relative else lit(self.epsilon)

        def inclusive_epsilon(diff: Column) -> Column:
            return diff.__le__(threshold)

        def exclusive_epsilon(diff: Column) -> Column:
            return diff.__lt__(threshold)

        in_epsilon = inclusive_epsilon if self.inclusive else exclusive_epsilon
        return left.isNull() & right.isNull() | left.isNotNull() & right.isNotNull() & in_epsilon(abs(left - right))


@dataclass(frozen=True)
class StringDiffComparator(DiffComparator):
    whitespace_agnostic: bool

    def equiv(self, left: Column, right: Column) -> Column:
        assert _is_column(left), left
        assert _is_column(right), right
        return left.eqNullSafe(right)


@dataclass(frozen=True)
class DurationDiffComparator(DiffComparator):
    duration: str
    inclusive: bool = True

    def as_inclusive(self) -> 'DurationDiffComparator':
        return dataclasses.replace(self, inclusive=True)

    def as_exclusive(self) -> 'DurationDiffComparator':
        return dataclasses.replace(self, inclusive=False)

    def equiv(self, left: Column, right: Column) -> Column:
        assert _is_column(left), left
        assert _is_column(right), right
        return left.eqNullSafe(right)


@dataclass(frozen=True)
class MapDiffComparator(DiffComparator):
    key_type: DataType
    value_type: DataType
    key_order_sensitive: bool

    def equiv(self, left: Column, right: Column) -> Column:
        assert _is_column(left), left
        assert _is_column(right), right
        return left.eqNullSafe(right)
